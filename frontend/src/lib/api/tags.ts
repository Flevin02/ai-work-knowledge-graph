import {backendApiUrl, readApiResponse, type ApiResponse} from '@/lib/api/client';
import type {
  DocumentTag,
  DocumentTagReviewBatchResponse,
  DocumentTagReviewDecision,
  DocumentTaggingRun,
  KnowledgeTagSummary,
} from '@/lib/types';

export async function createDocumentTaggingRun(
  spaceId: string,
  documentId: string
): Promise<DocumentTaggingRun> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/tagging-runs`, {
    method: 'POST',
  });
  return readApiResponse<DocumentTaggingRun>(response);
}

export type DocumentTaggingBatchResponse = {
  requestedCount: number;
  acceptedCount: number;
  documentIds: string[];
  rejectedDocumentIds: string[];
};

export async function submitDocumentTaggingBatch(
  spaceId: string,
  documentIds: string[]
): Promise<DocumentTaggingBatchResponse> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/tagging-batches`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({documentIds}),
  });
  return readApiResponse<DocumentTaggingBatchResponse>(response);
}

export type DocumentTagBatchReviewResponse = {
  requestedDocumentCount: number;
  reviewedDocumentCount: number;
  acceptedCount: number;
  rejectedCount: number;
};

export async function reviewDocumentTagsAcrossDocuments(
  spaceId: string,
  documentIds: string[],
  action: 'accept' | 'reject',
  reason?: string
): Promise<DocumentTagBatchReviewResponse> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/tag-review-batches`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({documentIds, action, reason}),
  });
  return readApiResponse<DocumentTagBatchReviewResponse>(response);
}

export async function getLatestDocumentTaggingRun(
  spaceId: string,
  documentId: string
): Promise<DocumentTaggingRun | null> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/tagging-runs/latest`, {
    method: 'GET',
    cache: 'no-store',
  });
  const payload = await response.json() as ApiResponse<DocumentTaggingRun>;
  if (payload.error && payload.code === 404) return null;
  if (!response.ok || payload.error) {
    throw new Error(payload.msg || '最近标签运行加载失败');
  }
  return payload.data;
}

export async function listDocumentTags(
  spaceId: string,
  documentId: string
): Promise<DocumentTag[]> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/tags`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<DocumentTag[]>(response);
}

export async function reviewDocumentTags(
  spaceId: string,
  documentId: string,
  reviews: DocumentTagReviewDecision[]
): Promise<DocumentTagReviewBatchResponse> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/tag-review-batches`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({reviews}),
  });
  return readApiResponse<DocumentTagReviewBatchResponse>(response);
}

export async function listConfirmedKnowledgeTags(spaceId: string): Promise<KnowledgeTagSummary[]> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/tags`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<KnowledgeTagSummary[]>(response);
}
