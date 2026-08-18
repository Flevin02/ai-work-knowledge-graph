import { backendApiUrl, readApiResponse } from '@/lib/api/client';
import type { KnowledgeSpace } from '@/lib/types';

export type CreateKnowledgeSpaceInput = {
  name: string;
  description?: string;
};

export async function listKnowledgeSpaces(): Promise<KnowledgeSpace[]> {
  const response = await fetch(`${backendApiUrl}/v1/spaces`, {
    method: 'GET',
    cache: 'no-store',
  });
  return readApiResponse<KnowledgeSpace[]>(response);
}

export async function createKnowledgeSpace(input: CreateKnowledgeSpaceInput): Promise<KnowledgeSpace> {
  const response = await fetch(`${backendApiUrl}/v1/spaces`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  return readApiResponse<KnowledgeSpace>(response);
}

export async function deleteKnowledgeSpace(spaceId: string): Promise<void> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${spaceId}`, {
    method: 'DELETE',
  });
  return readApiResponse<void>(response);
}
