import type {GraphNode, GraphEdge, NodeType, NodeStatus, EdgeStatus} from '@/lib/types';
import {backendApiUrl, readApiResponse} from '@/lib/api/client';

type GraphNodeResponse = {
  id: string;
  type: string;
  label: string;
  summary?: string;
  status: string;
  sourceIds: string[];
  createdAt: string;
  updatedAt: string;
};

type GraphEdgeResponse = {
  id: string;
  source: string;
  target: string;
  type: string;
  status: string;
  confidence: number;
  evidence: GraphEdge['evidence'];
  createdAt: string;
  updatedAt: string;
};

type GraphResponse = {
  nodes: GraphNodeResponse[];
  edges: GraphEdgeResponse[];
};

export async function getGraph(spaceId: string): Promise<{nodes: GraphNode[]; edges: GraphEdge[]}> {
  const response = await fetch(`${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/graph`, {
    method: 'GET',
    cache: 'no-store',
  });
  const graph = await readApiResponse<GraphResponse>(response);
  return {
    nodes: graph.nodes.map((node) => ({
      id: node.id,
      type: node.type as NodeType,
      label: node.label,
      summary: node.summary ?? '',
      status: node.status as NodeStatus,
      sourceIds: node.sourceIds,
      createdAt: node.createdAt,
      updatedAt: node.updatedAt,
    })),
    edges: graph.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: edge.type,
      status: edge.status as EdgeStatus,
      confidence: edge.confidence,
      evidence: edge.evidence,
      createdAt: edge.createdAt,
      updatedAt: edge.updatedAt,
    })),
  };
}
