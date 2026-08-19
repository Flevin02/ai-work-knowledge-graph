import type {
  AiExtractionChunkCompletedEvent,
  AiExtractionChunkStartedEvent,
  AiExtractionCompletedEvent,
  AiExtractionDeltaEvent,
  AiExtractionErrorEvent,
  AiExtractionRunDetail,
  AiExtractionRunStartedEvent,
  AiExtractionRunSummary,
  SourceDocument,
  SourceDocumentContent,
  SourceDocumentPage,
} from '@/lib/types';
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

export async function listSourceDocuments(
  spaceId: string,
  page = 1,
  pageSize = 12,
  signal?: AbortSignal
): Promise<SourceDocumentPage> {
  const searchParams = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents?${searchParams}`, {
    method: 'GET',
    cache: 'no-store',
    signal,
  });
  return readApiResponse<SourceDocumentPage>(response);
}

export async function getSourceDocumentContent(spaceId: string, documentId: string): Promise<SourceDocumentContent> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/content`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<SourceDocumentContent>(response);
}

export async function importSourceDocuments(spaceId: string, files: File[]): Promise<DocumentImportResponse> {
  const formData = new FormData();
  files.forEach((file) => formData.append('files', file));

  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents`, {
    method: 'POST',
    body: formData,
  });
  return readApiResponse<DocumentImportResponse>(response);
}

export async function deleteSourceDocument(spaceId: string, documentId: string): Promise<void> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}`, {
    method: 'DELETE',
  });
  return readApiResponse<void>(response);
}

export type AiExtractionStreamHandlers = {
  onRunStarted?: (event: AiExtractionRunStartedEvent) => void;
  onChunkStarted?: (event: AiExtractionChunkStartedEvent) => void;
  onDelta?: (event: AiExtractionDeltaEvent) => void;
  onChunkCompleted?: (event: AiExtractionChunkCompletedEvent) => void;
  onCompleted?: (event: AiExtractionCompletedEvent) => void;
  onError?: (event: AiExtractionErrorEvent) => void;
};

export async function streamDocumentExtraction(
  spaceId: string,
  documentId: string,
  handlers: AiExtractionStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/extractions`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  });
  if (!response.ok) {
    throw new Error(await readExtractionStreamError(response));
  }
  if (!response.body) {
    throw new Error('浏览器未提供可读取的 AI 抽取响应流');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let terminalEventReceived = false;

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    buffer = buffer.replace(/\r\n/g, '\n');

    let separatorIndex = buffer.indexOf('\n\n');
    while (separatorIndex >= 0) {
      const eventBlock = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      terminalEventReceived = dispatchExtractionStreamEvent(eventBlock, handlers)
        || terminalEventReceived;
      separatorIndex = buffer.indexOf('\n\n');
    }

    if (done) break;
  }

  if (buffer.trim()) {
    terminalEventReceived = dispatchExtractionStreamEvent(buffer, handlers)
      || terminalEventReceived;
  }
  if (!terminalEventReceived) {
    throw new Error('AI 抽取连接在收到完成或失败事件前中断');
  }
}

async function readExtractionStreamError(response: Response): Promise<string> {
  try {
    const payload = await response.json() as { msg?: string };
    return payload.msg || `AI 抽取请求失败（HTTP ${response.status}）`;
  } catch {
    return `AI 抽取请求失败（HTTP ${response.status}）`;
  }
}

function dispatchExtractionStreamEvent(
  eventBlock: string,
  handlers: AiExtractionStreamHandlers
): boolean {
  const lines = eventBlock.split('\n');
  const eventName = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
  const dataText = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n');
  if (!eventName || !dataText) return false;

  const payload: unknown = JSON.parse(dataText);
  switch (eventName) {
    case 'run_started':
      handlers.onRunStarted?.(payload as AiExtractionRunStartedEvent);
      return false;
    case 'chunk_started':
      handlers.onChunkStarted?.(payload as AiExtractionChunkStartedEvent);
      return false;
    case 'delta':
      handlers.onDelta?.(payload as AiExtractionDeltaEvent);
      return false;
    case 'chunk_completed':
      handlers.onChunkCompleted?.(payload as AiExtractionChunkCompletedEvent);
      return false;
    case 'completed':
      handlers.onCompleted?.(payload as AiExtractionCompletedEvent);
      return true;
    case 'error':
      handlers.onError?.(payload as AiExtractionErrorEvent);
      return true;
    default:
      return false;
  }
}

export async function listDocumentExtractions(spaceId: string, documentId: string): Promise<AiExtractionRunSummary[]> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/extractions`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<AiExtractionRunSummary[]>(response);
}

export async function getDocumentExtraction(
  spaceId: string,
  documentId: string,
  extractionId: string
): Promise<AiExtractionRunDetail> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/extractions/${encodeURIComponent(extractionId)}`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<AiExtractionRunDetail>(response);
}
