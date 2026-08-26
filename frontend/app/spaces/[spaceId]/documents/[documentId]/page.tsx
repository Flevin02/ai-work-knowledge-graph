import GraphWorkspace from '@/components/graph-workspace';
import type { GraphData } from '@/lib/types';

type DocumentDetailPageProps = {
  params: Promise<{spaceId: string; documentId: string}>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const emptyGraph: GraphData = {
  nodes: [],
  edges: [],
  documents: [],
};

function firstValue(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

export default async function DocumentDetailPage({params, searchParams}: DocumentDetailPageProps) {
  const route = await params;
  const query = await searchParams;

  return <GraphWorkspace
    initialGraph={emptyGraph}
    initialState={{
      spaceId: route.spaceId,
      graphMode: 'document',
      selectedNodeId: route.documentId,
      graphSearch: firstValue(query.graphSearch),
      documentId: route.documentId,
      evidenceId: firstValue(query.evidenceId),
    }}
  />;
}
