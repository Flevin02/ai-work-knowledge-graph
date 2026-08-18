import type { AiDocumentExtraction, AiExtractionRunDetail, AiExtractionRunSummary, SourceDocument, SourceDocumentContent } from '@/lib/types';
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
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<SourceDocument[]>(response);
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

export async function createDocumentExtraction(spaceId: string, documentId: string): Promise<AiDocumentExtraction> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/extractions`, {
    method: 'POST',
  });
  return readApiResponse<AiDocumentExtraction>(response);
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
