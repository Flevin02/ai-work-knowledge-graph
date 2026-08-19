'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Archive,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  Eye,
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
  Sparkles,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import GraphCanvas from './graph-canvas';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { createDocumentExtraction, deleteSourceDocument, getDocumentExtraction, getSourceDocumentContent, importSourceDocuments, listSourceDocuments } from '@/lib/api/documents';
import { createKnowledgeSpace, deleteKnowledgeSpace, listKnowledgeSpaces } from '@/lib/api/spaces';
import {
  nodeTypeColors,
  nodeTypeLabels,
  type EdgeStatus,
  type AiDocumentExtraction,
  type GraphData,
  type GraphEdge,
  type GraphNode,
  type KnowledgeSpace,
  type NodeType,
  type SourceDocument,
  type SourceDocumentContent,
} from '@/lib/types';

type GraphWorkspaceProps = { initialGraph: GraphData };
type View = 'graph' | 'documents' | 'review' | 'health';
type NoticeTone = 'success' | 'warning' | 'error' | 'loading';
type DocumentExtractionState = {
  status: 'processing' | 'success' | 'error';
  message?: string;
  extractionId?: string;
};
type DeleteConfirmation =
  | { kind: 'space'; item: KnowledgeSpace }
  | { kind: 'document'; item: SourceDocument };

const allTypes: Array<NodeType | 'all'> = ['all', 'project', 'department', 'person', 'task', 'document', 'meeting', 'risk', 'decision'];
const DOCUMENT_PAGE_SIZE = 12;
const DOCUMENT_PROCESSING_POLL_INTERVAL_MS = 3000;
const relationTypeLabels: Record<string, string> = {
  project_contains_feature: '项目包含功能',
  feature_contains_requirement: '功能包含需求',
  requirement_has_task: '需求包含任务',
  requirement_has_risk: '需求存在风险',
  task_assigned_to_person: '任务分配给人员',
  department_responsible_for_project: '部门负责项目',
  decision_affects_requirement: '决策影响需求',
};

function formatStatus(status: EdgeStatus) {
  return { suggested: '待审核', confirmed: '已采纳', rejected: '已拒绝', stale: '已失效' }[status];
}

function formatRelationType(relationType: string) {
  return relationTypeLabels[relationType] ?? '关联';
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

function toExtractionState(document: SourceDocument): DocumentExtractionState | undefined {
  const extraction = document.latestExtraction;
  if (!extraction || extraction.status === 'not_started') return undefined;
  if (extraction.status === 'processing') {
    return { status: 'processing', extractionId: extraction.extractionId, message: '服务端正在执行 AI 提取' };
  }
  if (extraction.status === 'completed') {
    return { status: 'success', extractionId: extraction.extractionId, message: '最近一次 AI 提取已完成' };
  }
  return { status: 'error', extractionId: extraction.extractionId, message: extraction.errorMessage || '最近一次 AI 提取失败' };
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
  const [previewDocument, setPreviewDocument] = useState<SourceDocument | null>(null);
  const [extractionPreview, setExtractionPreview] = useState<AiDocumentExtraction | null>(null);
  const [deletingDocumentId, setDeletingDocumentId] = useState<string | null>(null);
  const [deleteConfirmation, setDeleteConfirmation] = useState<DeleteConfirmation | null>(null);
  const [documentExtractionStates, setDocumentExtractionStates] = useState<Record<string, DocumentExtractionState>>({});
  const [loadingExtractionResultId, setLoadingExtractionResultId] = useState<string | null>(null);
  const [documentPage, setDocumentPage] = useState(1);
  const [documentTotal, setDocumentTotal] = useState(0);
  const [documentTotalPages, setDocumentTotalPages] = useState(0);
  const [documentRefreshKey, setDocumentRefreshKey] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const preservedDocumentNoticeSpaceIdRef = useRef<string | null>(null);

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
    let pollingErrorVisible = false;
    let pollingTimer: ReturnType<typeof setTimeout> | undefined;
    const abortController = new AbortController();

    const loadPersistedDocuments = async (isPolling = false) => {
      const shouldPreserveNotice = !isPolling
        && preservedDocumentNoticeSpaceIdRef.current === currentSpaceId;
      if (!isPolling && !shouldPreserveNotice) {
        setNotice('正在加载当前知识空间的真实来源资料。');
        setNoticeTone('loading');
      }
      try {
        const response = await listSourceDocuments(
          currentSpaceId,
          documentPage,
          DOCUMENT_PAGE_SIZE,
          abortController.signal
        );
        if (cancelled) return;

        if (!response.items.length && documentPage > 1 && response.totalPages > 0) {
          setDocumentPage(response.totalPages);
          return;
        }

        setPersistedDocuments(response.items);
        setDocumentPage(response.page);
        setDocumentTotal(response.total);
        setDocumentTotalPages(response.totalPages);
        setDocumentExtractionStates(Object.fromEntries(
          response.items.flatMap((document) => {
            const state = toExtractionState(document);
            return state ? [[document.id, state]] : [];
          }),
        ));
        setGraph((current) => ({
          ...current,
          documents: mergeDocuments(
            current.documents.filter((document) => !document.spaceId),
            response.items,
          ),
        }));
        const hasProcessingDocument = response.items.some(
          (document) => document.latestExtraction?.status === 'processing'
        );
        if (!isPolling && !shouldPreserveNotice) {
          setNotice(response.total
            ? `已加载当前空间第 ${response.page} 页，共 ${response.total} 份真实来源资料；图谱节点仍为虚构演示数据。`
            : '当前知识空间尚未导入真实来源资料；图谱节点仍为虚构演示数据。');
          setNoticeTone('success');
        } else if (pollingErrorVisible) {
          pollingErrorVisible = false;
          setNotice(hasProcessingDocument ? 'AI 提取状态刷新已恢复。' : 'AI 提取状态已更新。');
          setNoticeTone('success');
        }
        if (!isPolling && shouldPreserveNotice) {
          // 资料变更后的列表刷新只同步分页数据，保留导入或删除结果提示
          preservedDocumentNoticeSpaceIdRef.current = null;
        }

        if (hasProcessingDocument) {
          // 等本次请求完成后再刷新当前页，避免慢请求重叠并发
          pollingTimer = setTimeout(
            () => void loadPersistedDocuments(true),
            DOCUMENT_PROCESSING_POLL_INTERVAL_MS
          );
        }
      } catch (error) {
        if (cancelled) return;
        if (isPolling) {
          pollingErrorVisible = true;
          setNotice(`AI 提取状态刷新失败，将继续重试：${error instanceof Error ? error.message : '未知错误'}`);
          setNoticeTone('warning');
          pollingTimer = setTimeout(
            () => void loadPersistedDocuments(true),
            DOCUMENT_PROCESSING_POLL_INTERVAL_MS
          );
          return;
        }
        setPersistedDocuments([]);
        setDocumentTotal(0);
        setDocumentTotalPages(0);
        setDocumentExtractionStates({});
        preservedDocumentNoticeSpaceIdRef.current = null;
        setNotice(`来源资料加载失败：${error instanceof Error ? error.message : '未知错误'}`);
        setNoticeTone('error');
      }
    };

    void loadPersistedDocuments();
    return () => {
      cancelled = true;
      abortController.abort();
      if (pollingTimer) clearTimeout(pollingTimer);
    };
  }, [currentSpaceId, documentPage, documentRefreshKey]);

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
      setDocumentPage(1);
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

  const removeCurrentSpace = async (space: KnowledgeSpace) => {
    if (spaces.length <= 1) {
      setNotice('至少保留一个有效知识空间。');
      setNoticeTone('warning');
      return;
    }
    setIsManagingSpace(true);
    try {
      await deleteKnowledgeSpace(space.id);
      const remainingSpaces = spaces.filter((item) => item.id !== space.id);
      setSpaces(remainingSpaces);
      setDocumentPage(1);
      setCurrentSpaceId(remainingSpaces[0]?.id ?? null);
      setNotice(`知识空间“${space.name}”已移除，历史事实仍保留。`);
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

      // 标记当前空间的下一次列表刷新，避免覆盖本批次导入结果摘要
      preservedDocumentNoticeSpaceIdRef.current = currentSpaceId;
      setDocumentPage(1);
      setDocumentRefreshKey((current) => current + 1);

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

  const removeDocument = async (document: SourceDocument) => {
    if (!currentSpaceId) return;
    setDeletingDocumentId(document.id);
    try {
      await deleteSourceDocument(currentSpaceId, document.id);
      const remainingTotal = Math.max(0, documentTotal - 1);
      const remainingPages = Math.ceil(remainingTotal / DOCUMENT_PAGE_SIZE);
      // 删除后的列表刷新只同步有效页码和资料数据，保留本次删除结果提示
      preservedDocumentNoticeSpaceIdRef.current = currentSpaceId;
      setDocumentPage(Math.min(documentPage, Math.max(1, remainingPages)));
      setDocumentRefreshKey((current) => current + 1);
      setDocumentExtractionStates((current) => {
        const nextStates = { ...current };
        delete nextStates[document.id];
        return nextStates;
      });
      setPreviewDocument((current) => current?.id === document.id ? null : current);
      setNotice(`来源资料“${document.name}”已删除，相关图谱来源贡献已同步更新。`);
      setNoticeTone('success');
    } catch (error) {
      setNotice(`删除来源资料失败：${error instanceof Error ? error.message : '未知错误'}`);
      setNoticeTone('error');
    } finally {
      setDeletingDocumentId(null);
    }
  };

  const extractDocument = async (document: SourceDocument) => {
    if (!currentSpaceId) return;

    setDocumentExtractionStates((current) => ({
      ...current,
      [document.id]: { status: 'processing', message: '正在提取实体、关系和原文证据' },
    }));
    try {
      const extraction = await createDocumentExtraction(currentSpaceId, document.id);
      const entityCount = extraction.chunks.reduce((count, chunk) => count + chunk.extraction.entities.length, 0);
      const relationCount = extraction.chunks.reduce((count, chunk) => count + chunk.extraction.relations.length, 0);
      setExtractionPreview(extraction);
      setDocumentExtractionStates((current) => ({
        ...current,
        [document.id]: {
          status: 'success',
          extractionId: extraction.extractionId,
          message: `${extraction.chunkCount} 个分片，${entityCount} 个候选实体，${relationCount} 条候选关系`,
        },
      }));
      // 重新读取当前页，让成功运行保存的 AI 摘要替换资料卡片原始预览
      setDocumentRefreshKey((current) => current + 1);
    } catch (error) {
      setDocumentExtractionStates((current) => ({
        ...current,
        [document.id]: {
          status: 'error',
          message: error instanceof Error ? error.message : '未知错误',
        },
      }));
    }
  };

  const viewExtractionResult = async (document: SourceDocument) => {
    if (!currentSpaceId) return;

    setLoadingExtractionResultId(document.id);
    try {
      const currentState = documentExtractionStates[document.id];
      const completedExtractionId = currentState?.status === 'success'
        ? currentState.extractionId
        : document.latestCompletedExtractionId;
      if (!completedExtractionId) throw new Error('当前文档还没有可查看的成功抽取结果');

      // 查询完整抽取结果，支持页面刷新后重新打开历史结果
      const detail = await getDocumentExtraction(
        currentSpaceId,
        document.id,
        completedExtractionId
      );
      if (!detail.result) {
        throw new Error('抽取记录没有保存完整结果');
      }
      setExtractionPreview(detail.result);
    } catch (error) {
      setDocumentExtractionStates((current) => ({
        ...current,
        [document.id]: {
          status: 'error',
          message: error instanceof Error ? error.message : '历史结果加载失败',
        },
      }));
    } finally {
      setLoadingExtractionResultId(null);
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
              <select aria-label="选择知识空间" value={currentSpaceId ?? ''} onChange={(event) => { setDocumentPage(1); setCurrentSpaceId(event.target.value); }} disabled={!spaces.length || isManagingSpace}>
                {!spaces.length && <option value="">后端未连接</option>}
                {spaces.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}
              </select>
              <button className="space-icon-button" aria-label="新建知识空间" title="新建知识空间" onClick={() => setIsSpaceFormOpen((current) => !current)}><FolderPlus size={15} /></button>
              <button className="space-icon-button danger" aria-label="删除当前知识空间" title="删除当前知识空间" disabled={!currentSpace || spaces.length <= 1 || isManagingSpace} onClick={() => currentSpace && setDeleteConfirmation({ kind: 'space', item: currentSpace })}><Trash2 size={15} /></button>
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
            <button className={view === 'documents' ? 'nav-item active' : 'nav-item'} onClick={() => setView('documents')}><FileText size={17} /> 来源资料 <span>{documentTotal}</span></button>
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
              {documentTotal > 4 && <div className="persisted-source-more">当前空间共 {documentTotal} 份已持久化资料</div>}
            </div> : <div className="persisted-source-empty">尚未导入真实资料</div>}
          </section>

          <div className="sidebar-footer"><CircleHelp size={15} /> 关联建议必须有证据，并经过人工审核</div>
        </aside>

        <section className="content-area">
          <div className="page-heading">
            <div><div className="eyebrow">工作台 / {currentSpace?.name ?? '未连接知识空间'}</div><h1>{pageTitle}</h1><p>{pageDescription}</p></div>
            <div className="page-stats"><div><strong>{graph.nodes.length}</strong><span>演示节点</span></div><div><strong>{confirmedEdgeCount}</strong><span>演示关系</span></div><div><strong>{documentTotal}</strong><span>真实资料</span></div></div>
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

          {view === 'documents' && <DocumentPanel
            documents={persistedDocuments}
            onPreview={setPreviewDocument}
            onDelete={(document) => setDeleteConfirmation({ kind: 'document', item: document })}
            onExtract={(document) => void extractDocument(document)}
            onViewExtraction={(document) => void viewExtractionResult(document)}
            deletingDocumentId={deletingDocumentId}
            extractionStates={documentExtractionStates}
            loadingExtractionResultId={loadingExtractionResultId}
            page={documentPage}
            total={documentTotal}
            totalPages={documentTotalPages}
            onPageChange={setDocumentPage}
          />}
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
      {previewDocument && currentSpaceId && <DocumentPreviewModal
        document={previewDocument}
        spaceId={currentSpaceId}
        onClose={() => setPreviewDocument(null)}
      />}
      {extractionPreview && <AiExtractionPreviewModal
        extraction={extractionPreview}
        onClose={() => setExtractionPreview(null)}
      />}
      {deleteConfirmation && <DeleteConfirmationDialog
        target={deleteConfirmation}
        onCancel={() => setDeleteConfirmation(null)}
        onConfirm={() => {
          const target = deleteConfirmation;
          setDeleteConfirmation(null);
          if (target.kind === 'space') {
            void removeCurrentSpace(target.item);
            return;
          }
          void removeDocument(target.item);
        }}
      />}
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

function DocumentPanel({
  documents,
  onPreview,
  onDelete,
  onExtract,
  onViewExtraction,
  deletingDocumentId,
  extractionStates,
  loadingExtractionResultId,
  page,
  total,
  totalPages,
  onPageChange,
}: {
  documents: SourceDocument[];
  onPreview: (document: SourceDocument) => void;
  onDelete: (document: SourceDocument) => void;
  onExtract: (document: SourceDocument) => void;
  onViewExtraction: (document: SourceDocument) => void;
  deletingDocumentId: string | null;
  extractionStates: Record<string, DocumentExtractionState>;
  loadingExtractionResultId: string | null;
  page: number;
  total: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  if (!documents.length) {
    return <div className="state-card document-empty"><FileText size={28} /><h3>尚未导入来源资料</h3><p>点击右上角“导入资料”，选择 UTF-8 Markdown 或 TXT 文件。</p></div>;
  }

  return <>
    <div className="document-grid">
      {documents.map((document) => {
        const extractionState = extractionStates[document.id];
        const isExtracting = extractionState?.status === 'processing';
        const completedExtractionId = extractionState?.status === 'success'
          ? extractionState.extractionId
          : document.latestCompletedExtractionId;
        const latestRunCompleted = extractionState?.status === 'success'
          || (!extractionState && document.latestExtraction?.status === 'completed');
        const isLoadingResult = loadingExtractionResultId === document.id;
        const extractionButtonLabel = isExtracting
          ? '提取中'
          : extractionState?.status === 'error'
            ? '重新提取'
            : extractionState?.status === 'success'
              ? '再次提取'
              : 'AI 提取';
        const resultButtonLabel = isLoadingResult
          ? '加载中'
          : completedExtractionId && !latestRunCompleted
            ? '查看上次结果'
            : '查看结果';
        const resultUnavailableMessage = completedExtractionId
          ? undefined
          : isExtracting
            ? 'AI 正在提取，请稍后查看结果'
            : extractionState?.status === 'error'
              ? `AI 提取失败，请先点击“${extractionButtonLabel}”`
              : `暂无可查看结果，请先点击“${extractionButtonLabel}”`;
        return <article className="document-card" key={document.id}>
        <div className="document-card-head"><span className="document-kind"><FileText size={16} />{document.kind === 'markdown' ? 'Markdown' : 'TXT'}</span><div className="document-status-group"><span className="document-status">已解析</span>{extractionState && <span className={`ai-document-status ${extractionState.status}`} title={extractionState.message}>{isExtracting && <LoaderCircle className="spin" size={11} />}{extractionState.status === 'processing' ? 'AI 提取中' : extractionState.status === 'success' ? 'AI 已完成' : 'AI 提取失败'}</span>}</div></div>
        <h3 title={document.name}>{document.name}</h3>
        <p>{document.excerpt}</p>
        <div className="document-meta"><span>{formatFileSize(document.fileSize)}</span><span>{formatImportedAt(document.importedAt)}</span></div>
        <div className="document-hash" title={document.contentHash}>SHA-256 · {document.contentHash.slice(0, 16)}…</div>
        <div className="document-card-actions">
          <button className="secondary-button" onClick={() => onPreview(document)}><Eye size={14} /> 查看</button>
          <button className="secondary-button" disabled={isExtracting} onClick={() => onExtract(document)}>{isExtracting ? <LoaderCircle className="spin" size={14} /> : <Sparkles size={14} />} {extractionButtonLabel}</button>
          <span className="result-button-tip" data-tooltip={resultUnavailableMessage}>
            <button className="secondary-button" aria-label={resultUnavailableMessage || resultButtonLabel} disabled={!completedExtractionId || isLoadingResult} onClick={() => onViewExtraction(document)}>{isLoadingResult ? <LoaderCircle className="spin" size={14} /> : <FileText size={14} />} {resultButtonLabel}</button>
          </span>
          <button className="secondary-button danger-button" disabled={deletingDocumentId === document.id || isExtracting} onClick={() => onDelete(document)}>{deletingDocumentId === document.id ? <LoaderCircle className="spin" size={14} /> : <Trash2 size={14} />} 删除</button>
        </div>
      </article>;})}
    </div>
    {totalPages > 1 && <nav className="document-pagination" aria-label="来源资料分页">
      <button className="secondary-button" disabled={page <= 1} onClick={() => onPageChange(page - 1)}><ChevronLeft size={15} /> 上一页</button>
      <span>第 {page} / {totalPages} 页 · 共 {total} 份</span>
      <button className="secondary-button" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>下一页 <ChevronRight size={15} /></button>
    </nav>}
  </>;
}

function DeleteConfirmationDialog({
  target,
  onCancel,
  onConfirm,
}: {
  target: DeleteConfirmation;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const isSpace = target.kind === 'space';

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onCancel]);

  return <div className="document-preview-backdrop confirmation-backdrop" role="presentation" onClick={onCancel}>
    <section className="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-confirmation-title" aria-describedby="delete-confirmation-description" onClick={(event) => event.stopPropagation()}>
      <header className="confirmation-header">
        <span className="confirmation-icon"><AlertTriangle size={21} /></span>
        <div>
          <div className="eyebrow">{isSpace ? '知识空间 / 移除确认' : '来源资料 / 删除确认'}</div>
          <h2 id="delete-confirmation-title">{isSpace ? '确认移除知识空间？' : '确认删除来源资料？'}</h2>
        </div>
        <button className="space-icon-button" aria-label="关闭删除确认" title="关闭" onClick={onCancel}><X size={16} /></button>
      </header>
      <div className="confirmation-content">
        <div className="confirmation-target">
          {isSpace ? <Archive size={18} /> : <FileText size={18} />}
          <div><span>{isSpace ? '待移除知识空间' : '待删除来源资料'}</span><strong title={target.item.name}>{target.item.name}</strong></div>
        </div>
        <p id="delete-confirmation-description">{isSpace
          ? '移除后，该空间将不再出现在工作台中；来源资料和图谱事实仍会保留在本地数据库中。'
          : '删除后，仅由该资料支撑的图谱节点和关系会同步失效；原始文件与历史证据仍会保留。'}</p>
      </div>
      <footer className="confirmation-actions">
        <button className="ghost-button" autoFocus onClick={onCancel}>取消</button>
        <button className="secondary-button danger-button confirmation-submit" onClick={onConfirm}><Trash2 size={15} />{isSpace ? '确认移除' : '确认删除'}</button>
      </footer>
    </section>
  </div>;
}

function DocumentPreviewModal({
  document,
  spaceId,
  onClose,
}: {
  document: SourceDocument;
  spaceId: string;
  onClose: () => void;
}) {
  const [content, setContent] = useState<SourceDocumentContent | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [previewMode, setPreviewMode] = useState<'rendered' | 'source'>('rendered');
  const previewModeLabel = previewMode === 'rendered' ? '渲染预览' : '原文预览';

  useEffect(() => {
    let cancelled = false;
    setContent(null);
    setError(null);
    setIsLoading(true);

    const loadContent = async () => {
      try {
        const loadedContent = await getSourceDocumentContent(spaceId, document.id);
        if (cancelled) return;
        setContent(loadedContent);
      } catch (loadError) {
        if (cancelled) return;
        setError(loadError instanceof Error ? loadError.message : '原文加载失败');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    };

    void loadContent();
    return () => { cancelled = true; };
  }, [document.id, spaceId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
    <section className="document-preview-dialog" role="dialog" aria-modal="true" aria-labelledby="document-preview-title" onClick={(event) => event.stopPropagation()}>
      <header className="document-preview-header">
        <div>
          <div className="eyebrow">来源资料 / {previewModeLabel}</div>
          <h2 id="document-preview-title" title={document.name}>{document.name}</h2>
        </div>
        <button className="space-icon-button" aria-label={`关闭${previewModeLabel}`} title="关闭" onClick={onClose}><X size={16} /></button>
      </header>
      {isLoading && <div className="document-preview-state"><LoaderCircle className="spin" size={22} /><span>正在加载原文…</span></div>}
      {!isLoading && error && <div className="document-preview-state error"><AlertTriangle size={22} /><span>原文加载失败：{error}</span><button className="secondary-button" onClick={onClose}>关闭</button></div>}
      {!isLoading && !error && content && <>
        <div className="document-preview-meta">
          <div className="document-preview-mode" role="tablist" aria-label="原文预览模式">
            <button
              className={previewMode === 'rendered' ? 'preview-mode-button active' : 'preview-mode-button'}
              type="button"
              role="tab"
              aria-selected={previewMode === 'rendered'}
              onClick={() => setPreviewMode('rendered')}
            >
              <Eye size={13} /> 渲染预览
            </button>
            <button
              className={previewMode === 'source' ? 'preview-mode-button active' : 'preview-mode-button'}
              type="button"
              role="tab"
              aria-selected={previewMode === 'source'}
              onClick={() => setPreviewMode('source')}
            >
              <FileText size={13} /> 原文预览
            </button>
          </div>
          <span>SHA-256 · {content.contentHash.slice(0, 16)}…</span>
        </div>
        {previewMode === 'rendered'
          ? <article className="document-preview-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content.contentText}</ReactMarkdown></article>
          : <pre className="document-preview-content">{content.contentText}</pre>}
      </>}
    </section>
  </div>;
}

function AiExtractionPreviewModal({
  extraction,
  onClose,
}: {
  extraction: AiDocumentExtraction;
  onClose: () => void;
}) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  const entities = extraction.chunks.flatMap((chunk) => chunk.extraction.entities);
  const relations = extraction.chunks.flatMap((chunk) => chunk.extraction.relations);
  const evidences = extraction.chunks.flatMap((chunk) => chunk.extraction.evidences);
  const conflicts = extraction.chunks.flatMap((chunk) => chunk.extraction.conflicts);
  const entityNames = new Map(entities.map((entity) => [entity.candidateId, entity.name]));

  return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
    <section className="document-preview-dialog ai-extraction-dialog" role="dialog" aria-modal="true" aria-labelledby="ai-extraction-title" onClick={(event) => event.stopPropagation()}>
      <header className="document-preview-header">
        <div>
          <div className="eyebrow">来源资料 / AI 提取预览</div>
          <h2 id="ai-extraction-title" title={extraction.documentName}>{extraction.documentName}</h2>
        </div>
        <button className="space-icon-button" aria-label="关闭 AI 提取预览" title="关闭" onClick={onClose}><X size={16} /></button>
      </header>
      <div className="ai-extraction-meta">
        <span>{extraction.provider} · {extraction.model}</span>
        <span>Prompt {extraction.promptVersion} · Schema {extraction.schemaVersion}</span>
      </div>
      <div className="ai-extraction-summary">
        <div><strong>{extraction.chunkCount}</strong><span>分片</span></div>
        <div><strong>{entities.length}</strong><span>候选实体</span></div>
        <div><strong>{relations.length}</strong><span>候选关系</span></div>
        <div><strong>{evidences.length}</strong><span>原文证据</span></div>
        <div><strong>{conflicts.length}</strong><span>冲突</span></div>
      </div>
      <div className="ai-extraction-content">
        {extraction.chunks.map((chunk) => <section className="ai-chunk-card" key={chunk.chunkId}>
          <div className="ai-chunk-heading"><span>{chunk.sectionPath}</span><em>{chunk.chunkId}</em></div>
          <div className="ai-result-section">
            <h3>候选实体</h3>
            {chunk.extraction.entities.length
              ? <div className="ai-entity-list">{chunk.extraction.entities.map((entity) => <div key={entity.candidateId}><span>{entity.type}</span><strong>{entity.name}</strong><p>{entity.summary || '暂无摘要'}</p></div>)}</div>
              : <p className="ai-empty-result">当前分片未识别出实体</p>}
          </div>
          <div className="ai-result-section">
            <h3>候选关系</h3>
            {chunk.extraction.relations.length
              ? <div className="ai-relation-list">{chunk.extraction.relations.map((relation, index) => <div key={`${chunk.chunkId}-relation-${index}`}><strong>{entityNames.get(relation.sourceEntityId) ?? relation.sourceEntityId}</strong><span>{formatRelationType(relation.relationType)}</span><strong>{entityNames.get(relation.targetEntityId) ?? relation.targetEntityId}</strong><em>{Math.round(relation.confidence * 100)}%</em></div>)}</div>
              : <p className="ai-empty-result">当前分片未识别出关系</p>}
          </div>
          {chunk.extraction.evidences.length > 0 && <div className="ai-result-section"><h3>原文证据</h3><div className="ai-evidence-list">{chunk.extraction.evidences.map((evidence) => <blockquote key={evidence.evidenceId}>“{evidence.quote}”<span>{evidence.sectionPath}</span></blockquote>)}</div></div>}
          {chunk.extraction.conflicts.length > 0 && <div className="ai-result-section"><h3>冲突</h3><div className="ai-conflict-list">{chunk.extraction.conflicts.map((conflict, index) => <div key={`${chunk.chunkId}-conflict-${index}`}><AlertTriangle size={14} /><span>{conflict.description}</span></div>)}</div></div>}
        </section>)}
      </div>
    </section>
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
