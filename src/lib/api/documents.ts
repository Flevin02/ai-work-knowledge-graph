import type { SourceDocument } from '@/lib/types';

type ApiResponse<T> = {
  error: boolean;
  code: number;
  msg: string;
  traceId?: string;
  data: T;
};

export type DocumentImportFileResult = {
  originalName: string;
  status: 'imported' | 'duplicate' | 'failed';
  message: string;
  document?: SourceDocument;
};

export type DocumentImportResponse = {
  batchId: string;
  status: 'completed' | 'partial_failed' | 'failed';
  totalCount: number;
  importedCount: number;
  duplicateCount: number;
  failedCount: number;
  results: DocumentImportFileResult[];
};

const backendApiUrl = process.env.NEXT_PUBLIC_BACKEND_API_URL ?? 'http://localhost:4010/api';

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = await response.json() as ApiResponse<T>;
  if (!response.ok || payload.error) {
    const traceMessage = payload.traceId ? `（TraceId: ${payload.traceId}）` : '';
    throw new Error(`${payload.msg || '后端请求失败'}${traceMessage}`);
  }
  return payload.data;
}

export async function listSourceDocuments(): Promise<SourceDocument[]> {
  const response = await fetch(`${backendApiUrl}/v1/documents`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<SourceDocument[]>(response);
}

export async function importSourceDocuments(files: File[]): Promise<DocumentImportResponse> {
  const formData = new FormData();
  files.forEach((file) => formData.append('files', file));

  const response = await fetch(`${backendApiUrl}/v1/documents/import`, {
    method: 'POST',
    body: formData,
  });
  return readApiResponse<DocumentImportResponse>(response);
}
