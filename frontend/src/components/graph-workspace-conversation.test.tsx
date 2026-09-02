import {render, screen, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import GraphWorkspace from '@/components/graph-workspace';
import type {GraphData, KnowledgeSpace, SourceDocument} from '@/lib/types';

const router = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
}));

const spacesApi = vi.hoisted(() => ({
  createKnowledgeSpace: vi.fn(),
  deleteKnowledgeSpace: vi.fn(),
  listKnowledgeSpaces: vi.fn(),
}));

const documentsApi = vi.hoisted(() => ({
  deleteSourceDocument: vi.fn(),
  deleteSourceDocuments: vi.fn(),
  getDocumentExtraction: vi.fn(),
  getSourceDocumentContent: vi.fn(),
  importSourceDocuments: vi.fn(),
  listDocumentExtractionReviewStates: vi.fn(),
  listSourceDocuments: vi.fn(),
  reviewDocumentExtractionRelations: vi.fn(),
  submitDocumentExtractionBatch: vi.fn(),
  streamDocumentExtraction: vi.fn(),
  updateDocumentVersion: vi.fn(),
}));

const graphApi = vi.hoisted(() => ({
  getDocumentGraph: vi.fn(),
  getGraph: vi.fn(),
}));

const tagsApi = vi.hoisted(() => ({
  createDocumentTaggingRun: vi.fn(),
  getLatestDocumentTaggingRun: vi.fn(),
  listConfirmedKnowledgeTags: vi.fn(),
  listDocumentTags: vi.fn(),
  reviewDocumentTags: vi.fn(),
  reviewDocumentTagsAcrossDocuments: vi.fn(),
  submitDocumentTaggingBatch: vi.fn(),
}));

const associationsApi = vi.hoisted(() => ({
  createDocumentAssociationRun: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => router,
}));

vi.mock('@/components/graph-canvas', () => ({
  default: () => <div>graph canvas</div>,
}));

vi.mock('@/components/conversation-panel', () => ({
  default: ({spaceId, initialConversationId}: {spaceId: string; initialConversationId?: string}) => (
    <div>conversation panel {spaceId} {initialConversationId}</div>
  ),
}));

vi.mock('@/lib/api/spaces', () => spacesApi);
vi.mock('@/lib/api/documents', () => documentsApi);
vi.mock('@/lib/api/graph', () => graphApi);
vi.mock('@/lib/api/tags', () => tagsApi);
vi.mock('@/lib/api/associations', () => associationsApi);

const spaceId = '9007199254740995';
const conversationId = '9007199254740993';

const space: KnowledgeSpace = {
  id: spaceId,
  name: '年会筹备',
  status: 'active',
  createdAt: '2026-09-02T01:00:00Z',
  updatedAt: '2026-09-02T01:00:00Z',
};

const document: SourceDocument = {
  id: '9007199254740997',
  spaceId,
  name: '2026 年会活动方案.md',
  kind: 'markdown',
  documentType: 'prd',
  contentHash: 'hash-1',
  excerpt: '年会方案',
  status: 'active',
  importedAt: '2026-09-02T01:00:00Z',
  updatedAt: '2026-09-02T01:00:00Z',
};

const emptyGraph: GraphData = {
  nodes: [],
  edges: [],
  documents: [],
};

describe('GraphWorkspace conversation integration', () => {
  it('从 URL 初始状态进入问答视图并保持空间隔离的会话标识', async () => {
    spacesApi.listKnowledgeSpaces.mockResolvedValue([space]);
    documentsApi.listSourceDocuments.mockResolvedValue({
      items: [document],
      page: 1,
      pageSize: 12,
      total: 1,
      totalPages: 1,
    });
    graphApi.getGraph.mockResolvedValue(emptyGraph);
    graphApi.getDocumentGraph.mockResolvedValue({nodes: [], edges: []});
    tagsApi.listConfirmedKnowledgeTags.mockResolvedValue([]);

    render(
      <GraphWorkspace
        initialGraph={emptyGraph}
        initialState={{
          spaceId,
          view: 'conversation',
          conversationId,
        }}
      />,
    );

    await waitFor(() => expect(spacesApi.listKnowledgeSpaces).toHaveBeenCalled());
    expect(await screen.findByText(`conversation panel ${spaceId} ${conversationId}`)).toBeInTheDocument();
    expect(screen.getByRole('button', {name: /有据问答/})).toHaveClass('active');
  });
});
