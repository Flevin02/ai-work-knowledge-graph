import GraphWorkspace from '@/components/graph-workspace';
import type { GraphData } from '@/lib/types';

const emptyGraph: GraphData = {
  nodes: [],
  edges: [],
  documents: [],
};

export default function HomePage() {
  return <GraphWorkspace initialGraph={emptyGraph} />;
}
