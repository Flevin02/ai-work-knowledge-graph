import type { SourceDocument } from '@/lib/types';
import { backendApiUrl, readApiResponse } from '@/lib/api/client';

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

export async function listSourceDocuments(spaceId: string): Promise<SourceDocument[]> {
  const query = new URLSearchParams({ spaceId });
  const response = await fetch(`${backendApiUrl}/v1/documents?${query}`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<SourceDocument[]>(response);
}

export async function importSourceDocuments(spaceId: string, files: File[]): Promise<DocumentImportResponse> {
  const formData = new FormData();
  formData.append('spaceId', spaceId);
  files.forEach((file) => formData.append('files', file));

  const response = await fetch(`${backendApiUrl}/v1/documents/import`, {
    method: 'POST',
    body: formData,
  });
  return readApiResponse<DocumentImportResponse>(response);
}
