import GraphWorkspace from '@/components/graph-workspace';
import { demoGraph } from '@/lib/demo-data';

export default function HomePage() {
  return <GraphWorkspace initialGraph={demoGraph} />;
}
