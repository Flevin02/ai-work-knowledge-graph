export type NodeType =
  | 'project'
  | 'department'
  | 'person'
  | 'task'
  | 'document'
  | 'meeting'
  | 'risk'
  | 'decision';

export type NodeStatus = 'active' | 'completed' | 'pending' | 'conflict' | 'orphan';
export type EdgeStatus = 'suggested' | 'confirmed' | 'rejected' | 'stale';

export type Evidence = {
  sourceDocumentId: string;
  sourceDocumentName: string;
  quote: string;
  locator?: string;
  extractionMethod: 'ai' | 'rule' | 'user';
};

export type GraphNode = {
  id: string;
  type: NodeType;
  label: string;
  summary: string;
  status: NodeStatus;
  sourceIds: string[];
  createdAt: string;
  updatedAt: string;
};

export type GraphEdge = {
  id: string;
  source: string;
  target: string;
  type: string;
  status: EdgeStatus;
  confidence: number;
  evidence: Evidence[];
  createdAt: string;
  updatedAt: string;
};

export type SourceDocument = {
  id: string;
  spaceId?: string;
  name: string;
  kind: 'markdown' | 'txt' | 'docx' | 'pdf';
  documentType?: 'general' | 'prd';
  contentHash: string;
  excerpt: string;
  status: 'active' | 'changed' | 'missing' | 'parse_failed';
  fileSize?: number;
  importedAt: string;
  updatedAt: string;
  latestExtraction?: SourceDocumentExtractionSummary;
  latestCompletedExtractionId?: string;
};

export type SourceDocumentExtractionSummary = {
  extractionId?: string;
  status: 'not_started' | 'processing' | 'completed' | 'failed';
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
};

export type SourceDocumentPage = {
  items: SourceDocument[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
};

export type SourceDocumentContent = {
  id: string;
  spaceId: string;
  name: string;
  kind: 'markdown' | 'txt' | 'docx' | 'pdf';
  documentType?: string;
  contentHash: string;
  contentText: string;
  importedAt: string;
  updatedAt: string;
};

export type AiEntityCandidate = {
  candidateId: string;
  type: string;
  name: string;
  summary?: string;
  evidenceIds: string[];
};

export type AiRelationCandidate = {
  sourceEntityId: string;
  targetEntityId: string;
  relationType: string;
  confidence: number;
  evidenceIds: string[];
};

export type AiEvidenceCandidate = {
  evidenceId: string;
  sourceDocumentId: string;
  chunkId: string;
  sectionPath: string;
  quote: string;
};

export type AiConflictCandidate = {
  conflictType: string;
  description: string;
  evidenceIds: string[];
};

export type AiExtractionResult = {
  entities: AiEntityCandidate[];
  relations: AiRelationCandidate[];
  evidences: AiEvidenceCandidate[];
  conflicts: AiConflictCandidate[];
};

export type AiDocumentExtraction = {
  extractionId: string;
  status: 'processing' | 'completed' | 'failed';
  createdAt: string;
  completedAt?: string;
  documentId: string;
  documentName: string;
  documentType: 'general' | 'prd';
  provider: string;
  model: string;
  promptVersion: string;
  schemaVersion: string;
  sectionCount: number;
  chunkCount: number;
  chunks: Array<{
    chunkId: string;
    sectionPath: string;
    extraction: AiExtractionResult;
  }>;
};

export type AiExtractionRunSummary = {
  extractionId: string;
  status: 'processing' | 'completed' | 'failed';
  provider: string;
  model: string;
  promptVersion: string;
  schemaVersion: string;
  sectionCount: number;
  chunkCount: number;
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
};

export type AiExtractionRunDetail = {
  summary: AiExtractionRunSummary;
  result?: AiDocumentExtraction;
};

export type KnowledgeSpace = {
  id: string;
  name: string;
  description?: string;
  status: 'active';
  createdAt: string;
  updatedAt: string;
};

export type GraphData = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  documents: SourceDocument[];
};

export const nodeTypeLabels: Record<NodeType, string> = {
  project: '项目',
  department: '部门',
  person: '人员',
  task: '任务',
  document: '文档',
  meeting: '会议',
  risk: '风险',
  decision: '决策',
};

export const nodeTypeColors: Record<NodeType, string> = {
  project: '#8b5cf6',
  department: '#0ea5e9',
  person: '#10b981',
  task: '#f59e0b',
  document: '#94a3b8',
  meeting: '#ec4899',
  risk: '#ef4444',
  decision: '#14b8a6',
};
