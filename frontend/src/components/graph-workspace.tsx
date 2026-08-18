'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Archive,
  Check,
  ChevronRight,
  CircleHelp,
  FileText,
  Filter,
  FolderPlus,
  GitBranch,
  Inbox,
  LayoutDashboard,
  Link2,
  LoaderCircle,
  Search,
  ShieldCheck,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import GraphCanvas from './graph-canvas';
import { importSourceDocuments, listSourceDocuments } from '@/lib/api/documents';
import { createKnowledgeSpace, deleteKnowledgeSpace, listKnowledgeSpaces } from '@/lib/api/spaces';
import {
  nodeTypeColors,
  nodeTypeLabels,
  type EdgeStatus,
  type GraphData,
  type GraphEdge,
  type GraphNode,
  type KnowledgeSpace,
  type NodeType,
  type SourceDocument,
} from '@/lib/types';

type GraphWorkspaceProps = { initialGraph: GraphData };
type View = 'graph' | 'documents' | 'review' | 'health';
type NoticeTone = 'success' | 'warning' | 'error' | 'loading';

const allTypes: Array<NodeType | 'all'> = ['all', 'project', 'department', 'person', 'task', 'document', 'meeting', 'risk', 'decision'];

function formatStatus(status: EdgeStatus) {
  return { suggested: '待审核', confirmed: '已采纳', rejected: '已拒绝', stale: '已失效' }[status];
}

function issueCount(graph: GraphData) {
  const confirmedEdges = graph.edges.filter((edge) => edge.status === 'confirmed');
  const connected = new Set(confirmedEdges.flatMap((edge) => [edge.source, edge.target]));
  return graph.nodes.filter((node) => node.status === 'orphan' || !connected.has(node.id)).length
    + graph.nodes.filter((node) => node.status === 'conflict').length
    + graph.edges.filter((edge) => edge.status === 'suggested').length;
}

function mergeDocuments(current: SourceDocument[], incoming: SourceDocument[]) {
  const documentsById = new Map(current.map((document) => [document.id, document]));
  incoming.forEach((document) => documentsById.set(document.id, document));
  return Array.from(documentsById.values());
}

export default function GraphWorkspace({ initialGraph }: GraphWorkspaceProps) {
  const [graph, setGraph] = useState(initialGraph);
  const [view, setView] = useState<View>('graph');
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>('project-annual-party');
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<NodeType | 'all'>('all');
  const [notice, setNotice] = useState('演示图谱已加载，正在连接后端来源资料服务。');
  const [noticeTone, setNoticeTone] = useState<NoticeTone>('loading');
  const [spaces, setSpaces] = useState<KnowledgeSpace[]>([]);
  const [currentSpaceId, setCurrentSpaceId] = useState<string | null>(null);
  const [persistedDocuments, setPersistedDocuments] = useState<SourceDocument[]>([]);
  const [isSpaceFormOpen, setIsSpaceFormOpen] = useState(false);
  const [newSpaceName, setNewSpaceName] = useState('');
  const [newSpaceDescription, setNewSpaceDescription] = useState('');
  const [isManagingSpace, setIsManagingSpace] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;

    const loadSpaces = async () => {
      try {
        const loadedSpaces = await listKnowledgeSpaces();
        if (cancelled) return;
        setSpaces(loadedSpaces);
        setCurrentSpaceId((current) => current && loadedSpaces.some((space) => space.id === current)
          ? current
          : loadedSpaces[0]?.id ?? null);
      } catch (error) {
        if (cancelled) return;
        setNotice(`后端知识空间服务未连接，真实数据功能暂不可用：${error instanceof Error ? error.message : '未知错误'}`);
        setNoticeTone('error');
      }
    };

    void loadSpaces();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!currentSpaceId) return;
    let cancelled = false;

    const loadPersistedDocuments = async () => {
      setNotice('正在加载当前知识空间的真实来源资料。');
      setNoticeTone('loading');
      try {
        const documents = await listSourceDocuments(currentSpaceId);
        if (cancelled) return;
        setPersistedDocuments(documents);
        setGraph((current) => ({
          ...current,
          documents: mergeDocuments(
            current.documents.filter((document) => !document.spaceId),
            documents,
          ),
        }));
        setNotice(documents.length
          ? `已加载当前空间的 ${documents.length} 份真实来源资料；图谱节点仍为虚构演示数据。`
          : '当前知识空间尚未导入真实来源资料；图谱节点仍为虚构演示数据。');
        setNoticeTone('success');
      } catch (error) {
        if (cancelled) return;
        setPersistedDocuments([]);
        setNotice(`来源资料加载失败：${error instanceof Error ? error.message : '未知错误'}`);
        setNoticeTone('error');
      }
    };

    void loadPersistedDocuments();
    return () => { cancelled = true; };
  }, [currentSpaceId]);

  const currentSpace = spaces.find((space) => space.id === currentSpaceId) ?? null;

  const selectedNode = graph.nodes.find((node) => node.id === selectedNodeId) ?? null;
  const selectedEdges = graph.edges.filter((edge) => edge.source === selectedNodeId || edge.target === selectedNodeId);

  const visibleNodes = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return graph.nodes.filter((node) => {
      const matchesType = typeFilter === 'all' || node.type === typeFilter;
      const matchesSearch = !keyword || `${node.label} ${node.summary}`.toLowerCase().includes(keyword);
      return matchesType && matchesSearch;
    });
  }, [graph.nodes, search, typeFilter]);

  const visibleEdges = useMemo(() => {
    const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
    return graph.edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target) && edge.status !== 'rejected');
  }, [graph.edges, visibleNodes]);
  const pendingEdges = graph.edges.filter((edge) => edge.status === 'suggested');
  const confirmedEdgeCount = graph.edges.filter((edge) => edge.status === 'confirmed').length;

  const updateEdge = (edgeId: string, status: EdgeStatus) => {
    setGraph((current) => ({
      ...current,
      edges: current.edges.map((edge) => edge.id === edgeId ? { ...edge, status, updatedAt: new Date().toISOString() } : edge),
    }));
    setNotice(status === 'confirmed' ? '关联已采纳，图谱已更新。' : '关联建议已拒绝，并保留在审核记录中。');
    setNoticeTone('success');
  };

  const submitNewSpace = async () => {
    const name = newSpaceName.trim();
    if (!name) {
      setNotice('请输入知识空间名称。');
      setNoticeTone('warning');
      return;
    }

    setIsManagingSpace(true);
    try {
      const createdSpace = await createKnowledgeSpace({
        name,
        description: newSpaceDescription.trim() || undefined,
      });
      setSpaces((current) => [...current, createdSpace]);
      setCurrentSpaceId(createdSpace.id);
      setNewSpaceName('');
      setNewSpaceDescription('');
      setIsSpaceFormOpen(false);
      setNotice(`知识空间“${createdSpace.name}”已创建，并已准备独立本地目录。`);
      setNoticeTone('success');
    } catch (error) {
      setNotice(`创建知识空间失败：${error instanceof Error ? error.message : '未知错误'}`);
      setNoticeTone('error');
    } finally {
      setIsManagingSpace(false);
    }
  };

  const removeCurrentSpace = async () => {
    if (!currentSpace || spaces.length <= 1) {
      setNotice('至少保留一个有效知识空间。');
      setNoticeTone('warning');
      return;
    }
    if (!window.confirm(`确认移除知识空间“${currentSpace.name}”吗？来源资料和图谱事实会保留在本地数据库中。`)) {
      return;
    }

    setIsManagingSpace(true);
    try {
      await deleteKnowledgeSpace(currentSpace.id);
      const remainingSpaces = spaces.filter((space) => space.id !== currentSpace.id);
      setSpaces(remainingSpaces);
      setCurrentSpaceId(remainingSpaces[0]?.id ?? null);
      setNotice(`知识空间“${currentSpace.name}”已移除，历史事实仍保留。`);
      setNoticeTone('success');
    } catch (error) {
      setNotice(`移除知识空间失败：${error instanceof Error ? error.message : '未知错误'}`);
      setNoticeTone('error');
    } finally {
      setIsManagingSpace(false);
    }
  };

  const importFiles = async (files: FileList | null) => {
    if (!files?.length) return;
    if (!currentSpaceId) {
      setNotice('请先连接或创建知识空间。');
      setNoticeTone('warning');
      return;
    }
    setIsImporting(true);
    setNotice(`正在向后端导入 ${files.length} 份来源资料…`);
    setNoticeTone('loading');

    try {
      const response = await importSourceDocuments(currentSpaceId, Array.from(files));
      const returnedDocuments = response.results.flatMap((result) => result.document ? [result.document] : []);
      setPersistedDocuments((current) => mergeDocuments(current, returnedDocuments));
      setGraph((current) => ({ ...current, documents: mergeDocuments(current.documents, returnedDocuments) }));

      const summary = `导入完成：新增 ${response.importedCount} 份，重复 ${response.duplicateCount} 份，失败 ${response.failedCount} 份。`;
      const failureDetails = response.results
        .filter((result) => result.status === 'failed')
        .map((result) => `${result.originalName}：${result.message}`)
        .join('；');
      setNotice(failureDetails ? `${summary} ${failureDetails}` : summary);
      setNoticeTone(response.failedCount ? 'warning' : 'success');
    } catch (error) {
      setNotice(`真实导入失败，未使用浏览器本地数据兜底：${error instanceof Error ? error.message : '未知错误'}`);
      setNoticeTone('error');
    } finally {
      setIsImporting(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const resetDemo = () => {
    setGraph({ ...initialGraph, documents: mergeDocuments(initialGraph.documents, persistedDocuments) });
    setSelectedNodeId('project-annual-party');
    setSearch('');
    setTypeFilter('all');
    setNotice('虚构演示图谱已恢复，后端持久化的真实来源资料仍保留。');
    setNoticeTone('success');
  };

  const pageTitle = view === 'graph'
    ? '工作图谱'
    : view === 'documents'
      ? '来源资料'
      : view === 'review'
        ? '关系审核'
        : '知识健康检查';
  const pageDescription = view === 'graph'
    ? '从项目、任务、人员和资料之间的关系中找到工作上下文。'
    : view === 'documents'
      ? '查看当前知识空间中真实持久化的来源文件、解析状态和文本预览。'
      : view === 'review'
        ? '采纳 AI 建议前，先查看它引用的原文依据。'
        : '先处理会影响知识可信度的问题，再继续扩展图谱。';

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand-lockup">
          <div className="brand-mark"><GitBranch size={19} /></div>
          <div><div className="brand-name">知脉</div><div className="brand-subtitle">AI 工作知识图谱维护助手</div></div>
        </div>
        <div className="topbar-actions">
          <span className="local-badge"><span className="status-dot" /> 本地数据模式</span>
          <button className="ghost-button" onClick={resetDemo}>恢复演示资料</button>
          <button className="primary-button" disabled={isImporting || !currentSpaceId} onClick={() => fileInputRef.current?.click()}>{isImporting ? <LoaderCircle className="spin" size={16} /> : <Upload size={16} />} {isImporting ? '正在导入' : '导入资料'}</button>
          <input ref={fileInputRef} className="hidden-input" type="file" multiple accept=".md,.markdown,.txt" onChange={(event) => void importFiles(event.target.files)} />
        </div>
      </header>

      <div className="workspace-grid">
        <aside className="sidebar">
          <section className="space-card">
            <div className="eyebrow">当前知识空间</div>
            <div className="space-switcher">
              <select aria-label="选择知识空间" value={currentSpaceId ?? ''} onChange={(event) => setCurrentSpaceId(event.target.value)} disabled={!spaces.length || isManagingSpace}>
                {!spaces.length && <option value="">后端未连接</option>}
                {spaces.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}
              </select>
              <button className="space-icon-button" aria-label="新建知识空间" title="新建知识空间" onClick={() => setIsSpaceFormOpen((current) => !current)}><FolderPlus size={15} /></button>
              <button className="space-icon-button danger" aria-label="删除当前知识空间" title="删除当前知识空间" disabled={!currentSpace || spaces.length <= 1 || isManagingSpace} onClick={() => void removeCurrentSpace()}><Trash2 size={15} /></button>
            </div>
            <div className="space-title"><span className="space-icon"><Archive size={17} /></span>{currentSpace?.name ?? '等待后端连接'}</div>
            <p>{currentSpace?.description ?? '每个知识空间使用独立目录保存来源资料。'}</p>
            {isSpaceFormOpen && <div className="space-form">
              <input aria-label="知识空间名称" value={newSpaceName} onChange={(event) => setNewSpaceName(event.target.value)} placeholder="知识空间名称" maxLength={40} />
              <input aria-label="知识空间说明" value={newSpaceDescription} onChange={(event) => setNewSpaceDescription(event.target.value)} placeholder="用途说明（可选）" maxLength={200} />
              <div className="space-form-actions"><button className="ghost-button" onClick={() => setIsSpaceFormOpen(false)}>取消</button><button className="primary-button" disabled={isManagingSpace} onClick={() => void submitNewSpace()}>{isManagingSpace ? '创建中' : '创建'}</button></div>
            </div>}
          </section>

          <nav className="side-nav" aria-label="主导航">
            <button className={view === 'graph' ? 'nav-item active' : 'nav-item'} onClick={() => setView('graph')}><LayoutDashboard size={17} /> 工作图谱 <span>{graph.nodes.length}</span></button>
            <button className={view === 'documents' ? 'nav-item active' : 'nav-item'} onClick={() => setView('documents')}><FileText size={17} /> 来源资料 <span>{persistedDocuments.length}</span></button>
            <button className={view === 'review' ? 'nav-item active' : 'nav-item'} onClick={() => setView('review')}><Inbox size={17} /> 关系审核 <span className="warning-count">{pendingEdges.length}</span></button>
            <button className={view === 'health' ? 'nav-item active' : 'nav-item'} onClick={() => setView('health')}><ShieldCheck size={17} /> 知识健康 <span className="warning-count">{issueCount(graph)}</span></button>
          </nav>

          <section className="sidebar-section">
            <div className="section-heading">图谱类型</div>
            <div className="type-list">
              {allTypes.map((type) => (
                <button key={type} className={typeFilter === type ? 'type-filter selected' : 'type-filter'} onClick={() => { setTypeFilter(type); setView('graph'); }}>
                  {type === 'all' ? <span className="type-dot all-dot" /> : <span className="type-dot" style={{ background: nodeTypeColors[type] }} />}
                  {type === 'all' ? '全部节点' : nodeTypeLabels[type]}
                  <span>{type === 'all' ? graph.nodes.length : graph.nodes.filter((node) => node.type === type).length}</span>
                </button>
              ))}
            </div>
          </section>

          <section className="sidebar-section persisted-sources">
            <div className="section-heading">真实来源资料</div>
            {persistedDocuments.length ? <div className="persisted-source-list">
              {persistedDocuments.slice(0, 4).map((document) => <button className="persisted-source" key={document.id} title={document.name} onClick={() => setView('documents')}><FileText size={14} /><span>{document.name}</span><em>{document.kind === 'markdown' ? 'MD' : 'TXT'}</em></button>)}
              {persistedDocuments.length > 4 && <div className="persisted-source-more">另有 {persistedDocuments.length - 4} 份已持久化资料</div>}
            </div> : <div className="persisted-source-empty">尚未导入真实资料</div>}
          </section>

          <div className="sidebar-footer"><CircleHelp size={15} /> 关联建议必须有证据，并经过人工审核</div>
        </aside>

        <section className="content-area">
          <div className="page-heading">
            <div><div className="eyebrow">工作台 / {currentSpace?.name ?? '未连接知识空间'}</div><h1>{pageTitle}</h1><p>{pageDescription}</p></div>
            <div className="page-stats"><div><strong>{graph.nodes.length}</strong><span>演示节点</span></div><div><strong>{confirmedEdgeCount}</strong><span>演示关系</span></div><div><strong>{persistedDocuments.length}</strong><span>真实资料</span></div></div>
          </div>

          {notice && <div className={`notice ${noticeTone}`}>{noticeTone === 'loading' ? <LoaderCircle className="spin" size={16} /> : noticeTone === 'error' ? <AlertTriangle size={16} /> : <Check size={16} />} {notice}<button onClick={() => setNotice('')} aria-label="关闭提示"><X size={15} /></button></div>}

          {view === 'graph' && <>
            <div className="toolbar-card">
              <div className="search-box"><Search size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索项目、任务、人员或资料" /></div>
              <div className="toolbar-divider" />
              <div className="filter-label"><Filter size={15} /> {typeFilter === 'all' ? '全部类型' : nodeTypeLabels[typeFilter]}</div>
              <span className="toolbar-hint">实线：已采纳　虚线：待审核　红框：需关注</span>
            </div>
            <div className="graph-card"><GraphCanvas nodes={visibleNodes} edges={visibleEdges} selectedNodeId={selectedNodeId} onSelectNode={setSelectedNodeId} /><div className="graph-footnote">当前视图 {visibleNodes.length} 个节点 / {visibleEdges.length} 条关系 · 点击节点查看证据</div></div>
          </>}

          {view === 'documents' && <DocumentPanel documents={persistedDocuments} />}
          {view === 'review' && <ReviewPanel graph={graph} pendingEdges={pendingEdges} onUpdateEdge={updateEdge} />}
          {view === 'health' && <HealthPanel graph={graph} onSelectNode={(id) => { setSelectedNodeId(id); setView('graph'); }} />}
        </section>

        <aside className="detail-panel">
          {view === 'documents'
            ? <DocumentSidebar documents={persistedDocuments} space={currentSpace} />
            : selectedNode
              ? <NodeDetail node={selectedNode} edges={selectedEdges} graph={graph} onSelectNode={setSelectedNodeId} />
              : <div className="empty-detail"><Link2 size={28} /><p>选择一个节点查看它的上下文</p></div>}
        </aside>
      </div>
    </main>
  );
}

function formatFileSize(fileSize?: number) {
  if (fileSize == null) return '演示数据';
  if (fileSize < 1024) return `${fileSize} B`;
  return `${(fileSize / 1024).toFixed(1)} KB`;
}

function formatImportedAt(importedAt: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(importedAt));
}

function DocumentPanel({ documents }: { documents: SourceDocument[] }) {
  if (!documents.length) {
    return <div className="state-card document-empty"><FileText size={28} /><h3>尚未导入来源资料</h3><p>点击右上角“导入资料”，选择 UTF-8 Markdown 或 TXT 文件。</p></div>;
  }

  return <div className="document-grid">
    {documents.map((document) => <article className="document-card" key={document.id}>
      <div className="document-card-head"><span className="document-kind"><FileText size={16} />{document.kind === 'markdown' ? 'Markdown' : 'TXT'}</span><span className="document-status">已解析</span></div>
      <h3 title={document.name}>{document.name}</h3>
      <p>{document.excerpt}</p>
      <div className="document-meta"><span>{formatFileSize(document.fileSize)}</span><span>{formatImportedAt(document.importedAt)}</span></div>
      <div className="document-hash" title={document.contentHash}>SHA-256 · {document.contentHash.slice(0, 16)}…</div>
    </article>)}
  </div>;
}

function DocumentSidebar({ documents, space }: { documents: SourceDocument[]; space: KnowledgeSpace | null }) {
  const markdownCount = documents.filter((document) => document.kind === 'markdown').length;
  const textCount = documents.filter((document) => document.kind === 'txt').length;
  const totalSize = documents.reduce((sum, document) => sum + (document.fileSize ?? 0), 0);

  return <div className="detail-content document-sidebar">
    <div className="detail-kicker"><span className="type-dot" style={{ background: '#94a3b8' }} />来源资料<span className="status-pill active">本地持久化</span></div>
    <h2>{space?.name ?? '未连接空间'}</h2>
    <p className="detail-summary">原始文件保存在当前知识空间独立目录中，SQLite 只保存结构化索引、解析文本和证据定位。</p>
    <div className="document-summary-grid"><div><strong>{documents.length}</strong><span>全部资料</span></div><div><strong>{markdownCount}</strong><span>Markdown</span></div><div><strong>{textCount}</strong><span>TXT</span></div><div><strong>{formatFileSize(totalSize)}</strong><span>文件总量</span></div></div>
    <div className="detail-block"><div className="detail-label">当前阶段边界</div><div className="boundary-list"><span>✓ UTF-8 文本解析</span><span>✓ SHA-256 空间内去重</span><span>✓ 原始文件独立落盘</span><span>○ AI 抽取将在下一阶段接入</span></div></div>
  </div>;
}

function NodeDetail({ node, edges, graph, onSelectNode }: { node: GraphNode; edges: GraphEdge[]; graph: GraphData; onSelectNode: (id: string) => void }) {
  return <div className="detail-content">
    <div className="detail-kicker"><span className="type-dot" style={{ background: nodeTypeColors[node.type] }} />{nodeTypeLabels[node.type]}<span className={`status-pill ${node.status}`}>{node.status === 'conflict' ? '需要关注' : node.status === 'orphan' ? '未归档' : node.status === 'completed' ? '已完成' : '进行中'}</span></div>
    <h2>{node.label}</h2><p className="detail-summary">{node.summary}</p>
    <div className="detail-block"><div className="detail-label">关系上下文 <span>{edges.length}</span></div><div className="relation-list">{edges.map((edge) => { const otherId = edge.source === node.id ? edge.target : edge.source; const other = graph.nodes.find((item) => item.id === otherId); return <button key={edge.id} className="relation-row" onClick={() => onSelectNode(otherId)}><span className="relation-node"><span className="type-dot" style={{ background: other ? nodeTypeColors[other.type] : '#64748b' }} />{other?.label ?? otherId}</span><span className="relation-type">{edge.type}<em className={edge.status}>{formatStatus(edge.status)}</em></span><ChevronRight size={15} /></button>; })}</div></div>
    <div className="detail-block"><div className="detail-label">来源资料 <span>{node.sourceIds.length}</span></div><div className="source-list">{node.sourceIds.length ? node.sourceIds.map((id) => { const document = graph.documents.find((item) => item.id === id); return <div className="source-row" key={id}><FileText size={15} /><span>{document?.name ?? id}</span></div>; }) : <div className="muted-row">暂无来源，建议补充原始资料</div>}</div></div>
    {edges.find((edge) => edge.evidence.length)?.evidence[0] && <div className="evidence-box"><div className="evidence-title"><ShieldCheck size={15} /> 关系依据</div><p>“{edges.find((edge) => edge.evidence.length)?.evidence[0].quote}”</p><span>{edges.find((edge) => edge.evidence.length)?.evidence[0].sourceDocumentName} · {edges.find((edge) => edge.evidence.length)?.evidence[0].locator}</span></div>}
  </div>;
}

function ReviewPanel({ graph, pendingEdges, onUpdateEdge }: { graph: GraphData; pendingEdges: GraphEdge[]; onUpdateEdge: (id: string, status: EdgeStatus) => void }) {
  if (!pendingEdges.length) return <div className="state-card"><Check size={28} /><h3>没有待审核关系</h3><p>当前图谱的关系建议都已处理。</p></div>;
  return <div className="review-list">{pendingEdges.map((edge) => { const source = graph.nodes.find((node) => node.id === edge.source); const target = graph.nodes.find((node) => node.id === edge.target); const evidence = edge.evidence[0]; return <article className="review-card" key={edge.id}><div className="review-head"><span className="review-badge">AI 建议</span><span>置信度 {Math.round(edge.confidence * 100)}%</span></div><div className="review-relation"><strong>{source?.label}</strong><span>{edge.type}</span><strong>{target?.label}</strong></div><div className="review-evidence"><div><ShieldCheck size={15} /> 关联依据 · {evidence.sourceDocumentName}</div><p>“{evidence.quote}”</p><span>{evidence.locator}</span></div><div className="review-actions"><button className="secondary-button" onClick={() => onUpdateEdge(edge.id, 'rejected')}><X size={15} /> 拒绝</button><button className="primary-button" onClick={() => onUpdateEdge(edge.id, 'confirmed')}><Check size={15} /> 采纳关联</button></div></article>; })}</div>;
}

function HealthPanel({ graph, onSelectNode }: { graph: GraphData; onSelectNode: (id: string) => void }) {
  const confirmed = graph.edges.filter((edge) => edge.status === 'confirmed');
  const connected = new Set(confirmed.flatMap((edge) => [edge.source, edge.target]));
  const orphanNodes = graph.nodes.filter((node) => node.status === 'orphan' || !connected.has(node.id));
  const conflictNodes = graph.nodes.filter((node) => node.status === 'conflict');
  const suggested = graph.edges.filter((edge) => edge.status === 'suggested');
  const issues = [{ title: '待审核关联', count: suggested.length, tone: 'warning', icon: Inbox, items: suggested.map((edge) => ({ id: edge.source, label: `${graph.nodes.find((node) => node.id === edge.source)?.label} → ${graph.nodes.find((node) => node.id === edge.target)?.label}` })) }, { title: '孤立或未归档节点', count: orphanNodes.length, tone: 'danger', icon: Link2, items: orphanNodes.map((node) => ({ id: node.id, label: node.label })) }, { title: '可能存在冲突', count: conflictNodes.length, tone: 'danger', icon: AlertTriangle, items: conflictNodes.map((node) => ({ id: node.id, label: node.label })) }];
  return <div className="health-grid">{issues.map((issue) => <article className="health-card" key={issue.title}><div className={`health-icon ${issue.tone}`}><issue.icon size={18} /></div><div className="health-card-heading"><div><h3>{issue.title}</h3><p>{issue.count ? '建议现在处理' : '当前没有发现问题'}</p></div><strong>{issue.count}</strong></div>{issue.items.length > 0 && <div className="health-items">{issue.items.map((item) => <button key={item.id + item.label} onClick={() => onSelectNode(item.id)}>{item.label}<ChevronRight size={15} /></button>)}</div>}</article>)}</div>;
}
