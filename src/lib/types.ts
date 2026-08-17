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
  name: string;
  kind: 'markdown' | 'txt' | 'docx' | 'pdf';
  contentHash: string;
  excerpt: string;
  status: 'active' | 'changed' | 'missing' | 'parse_failed';
  fileSize?: number;
  importedAt: string;
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
