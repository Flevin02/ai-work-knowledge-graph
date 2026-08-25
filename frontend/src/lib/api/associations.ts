import {backendApiUrl, readApiResponse} from '@/lib/api/client';
import type {DocumentAssociationRun} from '@/lib/types';

/**
 * 创建一次显式开启 confirmed 标签补充通道的文档关联运行。
 */
export async function createDocumentAssociationRun(
  spaceId: string,
  documentId: string,
  includeConfirmedTags: boolean
): Promise<DocumentAssociationRun> {
  const query = new URLSearchParams({includeConfirmedTags: String(includeConfirmedTags)});
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/association-runs?${query.toString()}`, {
    method: 'POST',
  });
  return readApiResponse<DocumentAssociationRun>(response);
}
