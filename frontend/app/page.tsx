import GraphWorkspace from '@/components/graph-workspace';
import type { GraphData } from '@/lib/types';

type HomePageProps = {
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

export default async function HomePage({searchParams}: HomePageProps) {
  const query = await searchParams;
  const graphMode = firstValue(query.graphMode) === 'document' ? 'document' : 'entity';

  return <GraphWorkspace
    initialGraph={emptyGraph}
    initialState={{
      spaceId: firstValue(query.spaceId),
      graphMode,
      selectedNodeId: firstValue(query.selectedNodeId),
      graphSearch: firstValue(query.graphSearch),
      documentType: firstValue(query.documentType),
      documentRelationType: firstValue(query.documentRelationType),
    }}
  />;
}
