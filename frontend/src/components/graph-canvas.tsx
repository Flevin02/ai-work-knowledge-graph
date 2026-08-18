'use client';

import { useEffect, useRef } from 'react';
import cytoscape, { type Core, type ElementDefinition } from 'cytoscape';
import { nodeTypeColors, type GraphEdge, type GraphNode } from '@/lib/types';

type GraphCanvasProps = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string) => void;
};

export default function GraphCanvas({ nodes, edges, selectedNodeId, onSelectNode }: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Core | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    graphRef.current?.destroy();

    const elements: ElementDefinition[] = [
      ...nodes.map((node) => ({
        data: {
          id: node.id,
          label: node.label,
          color: nodeTypeColors[node.type],
          status: node.status,
        },
      })),
      ...edges.map((edge) => ({
        data: {
          id: edge.id,
          source: edge.source,
          target: edge.target,
          label: edge.type,
          status: edge.status,
        },
      })),
    ];

    const graph = cytoscape({
      container: containerRef.current,
      elements,
      minZoom: 0.35,
      maxZoom: 1.35,
      layout: {
        name: 'cose',
        animate: false,
        fit: true,
        padding: 56,
        idealEdgeLength: 180,
        nodeDimensionsIncludeLabels: true,
        nodeOverlap: 12,
        componentSpacing: 100,
      },
      style: [
        {
          selector: 'node',
          style: {
            label: 'data(label)',
            'background-color': 'data(color)',
            color: '#e2e8f0',
            'font-size': 12,
            'font-family': 'Inter, system-ui, sans-serif',
            'text-wrap': 'wrap',
            'text-max-width': '110px',
            'text-valign': 'bottom',
            'text-margin-y': 9,
            width: 22,
            height: 22,
            'border-width': 2,
            'border-color': '#1e293b',
            'overlay-opacity': 0,
          },
        },
        {
          selector: 'node[status = "conflict"]',
          style: { 'border-color': '#f87171', 'border-width': 4 },
        },
        {
          selector: 'node[status = "orphan"]',
          style: { 'border-color': '#fbbf24', 'border-width': 3 },
        },
        {
          selector: 'edge',
          style: {
            width: 1.4,
            'line-color': '#475569',
            'target-arrow-color': '#475569',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            'arrow-scale': 0.7,
            opacity: 0.8,
          },
        },
        {
          selector: 'edge[status = "suggested"]',
          style: { 'line-color': '#fbbf24', 'target-arrow-color': '#fbbf24', 'line-style': 'dashed', opacity: 1 },
        },
        {
          selector: 'edge[status = "stale"]',
          style: { 'line-color': '#f87171', 'target-arrow-color': '#f87171', 'line-style': 'dashed' },
        },
        {
          selector: '.selected',
          style: { 'border-color': '#ffffff', 'border-width': 4, width: 29, height: 29 },
        },
      ],
    });

    graph.on('tap', 'node', (event) => onSelectNode(event.target.id()));
    graphRef.current = graph;

    return () => {
      graph.destroy();
      graphRef.current = null;
    };
  }, [edges, nodes, onSelectNode]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;

    graph.nodes().removeClass('selected');
    if (selectedNodeId && graph.getElementById(selectedNodeId).length) {
      graph.getElementById(selectedNodeId).addClass('selected');
    }
  }, [edges, nodes, selectedNodeId]);

  return <div ref={containerRef} className="graph-canvas" aria-label="工作知识关系图谱" />;
}
