'use client';

import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react';
import cytoscape, { type Core, type ElementDefinition } from 'cytoscape';
import { nodeTypeColors, type GraphEdge, type GraphNode } from '@/lib/types';

type GraphCanvasProps = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string) => void;
  ariaLabel?: string;
  formatEdgeType?: (edgeType: string) => string;
  /** 会话视口快照的存储键；提供后刷新或返回图谱时恢复缩放与平移 */
  viewportStorageKey?: string;
};

const edgeStatusLabels: Record<GraphEdge['status'], string> = {
  suggested: '待审核',
  confirmed: '已确认',
  rejected: '已拒绝',
  stale: '已失效',
};

export default function GraphCanvas({
  nodes,
  edges,
  selectedNodeId,
  onSelectNode,
  ariaLabel = '工作知识关系图谱',
  formatEdgeType,
  viewportStorageKey,
}: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Core | null>(null);
  const focusNodeRef = useRef<(nodeId: string) => void>(() => undefined);
  const clearInteractionFocusRef = useRef<() => void>(() => undefined);
  const focusedNodeIdRef = useRef<string | null>(null);
  const [focusedNodeId, setFocusedNodeId] = useState<string | null>(null);
  const keyboardHelpId = useId();
  // 视口恢复：内容签名去重、节点集对照、最近视口和会话快照
  const viewportStorageKeyRef = useRef<string | undefined>(undefined);
  const elementsSignatureRef = useRef<string | null>(null);
  const nodeIdsSignatureRef = useRef<string | null>(null);
  const lastViewportRef = useRef<{ zoom: number; pan: { x: number; y: number } } | null>(null);
  const viewportSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  viewportStorageKeyRef.current = viewportStorageKey;

  const updateFocusedNode = (nodeId: string | null) => {
    focusedNodeIdRef.current = nodeId;
    setFocusedNodeId(nodeId);
  };

  useEffect(() => {
    if (!containerRef.current) return;

    // 图谱数据切换后旧节点不再属于当前视图，清空临时交互焦点和播报状态。
    updateFocusedNode(null);

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
          label: `${formatEdgeType?.(edge.type) ?? edge.type} · ${edgeStatusLabels[edge.status]}`,
          status: edge.status,
        },
      })),
    ];

    // 内容签名一致时复用现有实例：选中、父组件重渲染和无关刷新不再重置布局与视口。
    const elementsSignature = JSON.stringify(elements);
    if (elementsSignatureRef.current === elementsSignature) return;
    elementsSignatureRef.current = elementsSignature;

    // 节点集对照：同节点集的边/样式变化保留视野，节点集变化回退默认适配
    const nodeIdsSignature = JSON.stringify(nodes.map((node) => node.id));
    const sameNodeSet = nodeIdsSignatureRef.current !== null
      && nodeIdsSignatureRef.current === nodeIdsSignature;
    nodeIdsSignatureRef.current = nodeIdsSignature;

    // 销毁前记录最近视口，供同节点集重建后恢复
    if (graphRef.current) {
      lastViewportRef.current = { zoom: graphRef.current.zoom(), pan: graphRef.current.pan() };
    }
    graphRef.current?.destroy();

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
        {
          selector: '.interaction-muted',
          style: { opacity: 0.22 },
        },
        {
          selector: '.interaction-current',
          style: { 'border-color': '#ffffff', 'border-width': 4, width: 31, height: 31, opacity: 1 },
        },
        {
          selector: '.interaction-neighbor',
          style: { 'border-color': '#bfdbfe', 'border-width': 3, width: 26, height: 26, opacity: 1 },
        },
        {
          selector: '.interaction-edge',
          style: {
            width: 2.6,
            label: 'data(label)',
            color: '#e2e8f0',
            'font-size': 10,
            'font-family': 'Inter, system-ui, sans-serif',
            'text-background-color': '#0f172a',
            'text-background-opacity': 0.92,
            'text-background-padding': '3px',
            'text-rotation': 'autorotate',
            'line-color': '#e2e8f0',
            'target-arrow-color': '#e2e8f0',
            opacity: 1,
          },
        },
      ],
    });

    const clearInteractionFocus = () => {
      graph.elements().removeClass('interaction-muted interaction-current interaction-neighbor interaction-edge');
      updateFocusedNode(null);
    };

    // 视口恢复仅在已有节点集上执行：空图或全新节点集仍走布局默认适配
    const restoreViewport = (snapshot: { zoom: number; pan: { x: number; y: number } } | null) => {
      if (!snapshot || !nodes.length) return false;
      const clampedZoom = Math.min(Math.max(snapshot.zoom, graph.minZoom()), graph.maxZoom());
      graph.zoom(clampedZoom);
      graph.pan(snapshot.pan);
      return true;
    };
    const syncViewportDebug = () => {
      containerRef.current?.setAttribute('data-zoom', graph.zoom().toFixed(3));
    };

    let restored = false;
    if (sameNodeSet) {
      restored = restoreViewport(lastViewportRef.current);
    }
    if (!restored && viewportStorageKeyRef.current) {
      try {
        const rawSnapshot = sessionStorage.getItem(viewportStorageKeyRef.current);
        if (rawSnapshot) {
          restored = restoreViewport(JSON.parse(rawSnapshot) as { zoom: number; pan: { x: number; y: number } });
        }
      } catch {
        // 会话快照损坏时忽略，回退布局默认视口
      }
    }
    // 无论是否恢复快照，都同步一次初始视口标记
    syncViewportDebug();

    // 会话内保存最新视口：刷新或从资料视图返回图谱时按空间与图模式恢复
    graph.on('viewport', () => {
      const snapshot = { zoom: graph.zoom(), pan: graph.pan() };
      lastViewportRef.current = snapshot;
      syncViewportDebug();
      const storageKey = viewportStorageKeyRef.current;
      if (!storageKey) return;
      if (viewportSaveTimerRef.current) clearTimeout(viewportSaveTimerRef.current);
      viewportSaveTimerRef.current = setTimeout(() => {
        try {
          sessionStorage.setItem(storageKey, JSON.stringify(snapshot));
        } catch {
          // 会话存储不可用时静默降级，不影响图谱交互
        }
      }, 300);
    });

    const focusNode = (nodeId: string) => {
      const currentNode = graph.getElementById(nodeId);
      if (!currentNode.isNode()) return;

      const neighborhood = currentNode.closedNeighborhood();
      graph.elements().removeClass('interaction-muted interaction-current interaction-neighbor interaction-edge');
      graph.elements().addClass('interaction-muted');
      neighborhood.nodes().removeClass('interaction-muted').addClass('interaction-neighbor');
      neighborhood.edges().removeClass('interaction-muted').addClass('interaction-edge');
      currentNode.removeClass('interaction-neighbor').addClass('interaction-current');
      updateFocusedNode(nodeId);
    };

    graph.on('tap', 'node', (event) => {
      // 保持既有节点选中语义，同时让画布进入可继续键盘浏览的焦点范围。
      containerRef.current?.focus({preventScroll: true});
      onSelectNode(event.target.id());
    });
    graph.on('mouseover', 'node', (event) => focusNode(event.target.id()));
    graph.on('mouseout', 'node', clearInteractionFocus);
    focusNodeRef.current = focusNode;
    clearInteractionFocusRef.current = clearInteractionFocus;
    graphRef.current = graph;

    return () => {
      if (viewportSaveTimerRef.current) clearTimeout(viewportSaveTimerRef.current);
      viewportSaveTimerRef.current = null;
      elementsSignatureRef.current = null;
      nodeIdsSignatureRef.current = null;
      graph.destroy();
      graphRef.current = null;
      focusNodeRef.current = () => undefined;
      clearInteractionFocusRef.current = () => undefined;
    };
  }, [edges, formatEdgeType, nodes, onSelectNode]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;

    graph.nodes().removeClass('selected');
    if (selectedNodeId && graph.getElementById(selectedNodeId).length) {
      graph.getElementById(selectedNodeId).addClass('selected');
    }
  }, [edges, nodes, selectedNodeId]);

  const focusedNode = nodes.find((node) => node.id === focusedNodeId);
  const focusedNeighborCount = focusedNode
    ? edges.filter((edge) => edge.source === focusedNode.id || edge.target === focusedNode.id).length
    : 0;

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      clearInteractionFocusRef.current();
      return;
    }
    if (!nodes.length) return;

    const currentIndex = nodes.findIndex((node) => node.id === (focusedNodeIdRef.current ?? selectedNodeId));
    const navigationOffset = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1
      : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1
        : 0;
    let nextIndex = currentIndex;

    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = nodes.length - 1;
    if (navigationOffset) nextIndex = currentIndex < 0
      ? navigationOffset > 0 ? 0 : nodes.length - 1
      : (currentIndex + navigationOffset + nodes.length) % nodes.length;
    if (event.key === 'Enter' || event.key === ' ') {
      const currentNodeId = focusedNodeIdRef.current ?? selectedNodeId;
      if (!currentNodeId) return;
      event.preventDefault();
      onSelectNode(currentNodeId);
      return;
    }
    if (!navigationOffset && event.key !== 'Home' && event.key !== 'End') return;

    event.preventDefault();
    focusNodeRef.current(nodes[nextIndex].id);
  };

  const handleBlur = () => {
    clearInteractionFocusRef.current();
  };

  return <>
    <div
      ref={containerRef}
      className="graph-canvas"
      role="region"
      tabIndex={0}
      aria-label={ariaLabel}
      aria-describedby={keyboardHelpId}
      onBlur={handleBlur}
      onKeyDown={handleKeyDown}
    />
    <span id={keyboardHelpId} className="sr-only">使用方向键聚焦节点，按 Enter 或空格选择节点，按 Escape 清除高亮。</span>
    <span className="sr-only" aria-live="polite">{focusedNode
      ? `当前聚焦：${focusedNode.label}，${focusedNeighborCount} 条一跳关系。`
      : ''}</span>
  </>;
}
