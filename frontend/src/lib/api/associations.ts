import {backendApiUrl, readApiResponse} from '@/lib/api/client';
import type {DocumentAssociationRun} from '@/lib/types';

/**
 * 创建一次文档关联运行，可显式开启 confirmed 标签补充与语义候选融合通道。
 */
export async function createDocumentAssociationRun(
  spaceId: string,
  documentId: string,
  includeConfirmedTags: boolean,
  includeSemanticCandidates = false
): Promise<DocumentAssociationRun> {
  const query = new URLSearchParams({
    includeConfirmedTags: String(includeConfirmedTags),
    includeSemanticCandidates: String(includeSemanticCandidates),
  });
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/association-runs?${query.toString()}`, {
    method: 'POST',
  });
  return readApiResponse<DocumentAssociationRun>(response);
}
