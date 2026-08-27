'use client';

import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useRouter} from 'next/navigation';
import {
    AlertTriangle,
    Archive,
    Check,
    ChevronRight,
    CircleHelp,
    Eye,
    FileText,
    FilterX,
    FolderPlus,
    GitBranch,
    Inbox,
    LayoutDashboard,
    Link2,
    LoaderCircle,
    MapPin,
    Search,
    ShieldCheck,
    Sparkles,
    Tags,
    Trash2,
    Upload,
    X,
} from 'lucide-react';
import GraphCanvas from './graph-canvas';
import DocumentGraphSidebar from './document-graph-sidebar';
import SelectMenu from './select-menu';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
    deleteSourceDocuments,
    deleteSourceDocument,
    getDocumentExtraction,
    getSourceDocumentContent,
    importSourceDocuments,
    listDocumentExtractionReviewStates,
    listSourceDocuments,
    reviewDocumentExtractionRelations,
    submitDocumentExtractionBatch,
    streamDocumentExtraction
} from '@/lib/api/documents';
import {getDocumentGraph, getGraph} from '@/lib/api/graph';
import {createDocumentAssociationRun} from '@/lib/api/associations';
import {createKnowledgeSpace, deleteKnowledgeSpace, listKnowledgeSpaces} from '@/lib/api/spaces';
import {
    createDocumentTaggingRun,
    getLatestDocumentTaggingRun,
    listConfirmedKnowledgeTags,
    listDocumentTags,
    reviewDocumentTags,
} from '@/lib/api/tags';
import {
    nodeTypeColors,
    nodeTypeLabels,
    type EdgeStatus,
    type AiChunkExtraction,
    type AiDocumentExtraction,
    type AiRelationReviewAction,
    type DocumentTag,
    type DocumentTagReviewAction,
    type DocumentTaggingRun,
    type DocumentAssociationRun,
    type GraphData,
    type GraphEdge,
    type DocumentGraphData,
    type DocumentGraphEvidence,
    type GraphNode,
    type KnowledgeSpace,
    type KnowledgeTagSummary,
    type SourceDocument,
    type SourceDocumentContent,
    type SourceDocumentKind,
} from '@/lib/types';

type GraphMode = 'entity' | 'document';
export type GraphWorkspaceInitialState = {
    spaceId?: string;
    graphMode?: GraphMode;
    selectedNodeId?: string;
    graphSearch?: string;
    documentType?: string;
    documentRelationType?: string;
    documentId?: string;
    evidenceId?: string;
};
type GraphWorkspaceProps = {
    initialGraph: GraphData;
    initialState?: GraphWorkspaceInitialState;
};
type View = 'graph' | 'documents' | 'health';
type NoticeTone = 'success' | 'warning' | 'error' | 'loading';
type DocumentPreviewTab = 'rendered' | 'source' | 'ai';
type DocumentPreviewSelection = {
    document: SourceDocument;
    initialTab: DocumentPreviewTab;
    evidence?: DocumentGraphEvidence;
};
type DocumentExtractionState = {
    status: 'processing' | 'success' | 'error';
    message?: string;
    extractionId?: string;
};
type AiExtractionViewState = {
    documentId: string;
    documentName: string;
    status: 'connecting' | 'processing' | 'completed' | 'error';
    extractionId?: string;
    provider?: string;
    model?: string;
    promptVersion?: string;
    schemaVersion?: string;
    currentChunkId?: string;
    currentSectionPath?: string;
    currentChunkIndex: number;
    chunkCount: number;
    chunks: AiChunkExtraction[];
    rawOutput: string;
    message: string;
    result?: AiDocumentExtraction;
};
type AiRelationReviewStatus = 'accepted' | 'rejected';
type AiRelationReviewSelection = Record<string, boolean>;
type AiRelationReviewDecision = {
    relationKey: string;
    chunkId: string;
    relationIndex: number;
    action: AiRelationReviewAction;
};
type DeleteConfirmation =
    | { kind: 'space'; item: KnowledgeSpace }
    | { kind: 'document'; item: SourceDocument }
    | { kind: 'documents'; items: SourceDocument[] };

const DOCUMENT_PAGE_SIZE = 12;
const DOCUMENT_PROCESSING_POLL_INTERVAL_MS = 3000;
const documentKindLabels: Record<SourceDocumentKind, string> = {
    markdown: 'Markdown',
    txt: 'TXT',
    docx: 'DOCX',
    pdf: 'PDF',
};
const documentTypeLabels: Record<string, string> = {
    general: '通用资料',
    prd: 'PRD',
};
const documentRelationTypeLabels: Record<string, string> = {
    related_to: '相关',
    references: '引用',
    supports: '支持',
    updates: '更新',
    conflicts_with: '冲突',
};
const relationTypeLabels: Record<string, string> = {
    project_contains_feature: '项目包含功能',
    feature_contains_requirement: '功能包含需求',
    requirement_has_task: '需求包含任务',
    requirement_has_risk: '需求存在风险',
    task_assigned_to_person: '任务分配给人员',
    department_responsible_for_project: '部门负责项目',
    decision_affects_requirement: '决策影响需求',
};
const documentTagStatusLabels: Record<DocumentTag['status'], string> = {
    suggested: '待审核',
    confirmed: '已确认',
    rejected: '已拒绝',
    stale: '需重新评估',
};

function formatStatus(status: EdgeStatus) {
    return {suggested: '待审核', confirmed: '已采纳', rejected: '已拒绝', stale: '已失效'}[status];
}

function formatRelationType(relationType: string) {
    return relationTypeLabels[relationType] ?? '关联';
}

function getAiRelationReviewKey(extractionId: string, relationKey: string) {
    return `${extractionId}:${relationKey}`;
}

function formatDocumentTagStatus(status: DocumentTag['status']) {
    return documentTagStatusLabels[status];
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
        return {status: 'processing', extractionId: extraction.extractionId, message: '服务端正在执行 AI 提取'};
    }
    if (extraction.status === 'completed') {
        return {status: 'success', extractionId: extraction.extractionId, message: '最近一次 AI 提取已完成'};
    }
    return {
        status: 'error',
        extractionId: extraction.extractionId,
        message: extraction.errorMessage || '最近一次 AI 提取失败'
    };
}

export default function GraphWorkspace({initialGraph, initialState}: GraphWorkspaceProps) {
    const router = useRouter();
    const [graph, setGraph] = useState(initialGraph);
    const [documentGraph, setDocumentGraph] = useState<DocumentGraphData>({nodes: [], edges: []});
    const [loadedDocumentGraphSpaceId, setLoadedDocumentGraphSpaceId] = useState<string | null>(null);
    const [graphMode, setGraphMode] = useState<GraphMode>(initialState?.graphMode ?? 'entity');
    const [view, setView] = useState<View>('graph');
    const [selectedNodeId, setSelectedNodeId] = useState<string | null>(
        initialState?.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null
    );
    const [search, setSearch] = useState(initialState?.graphSearch ?? '');
    const [documentTypeFilter, setDocumentTypeFilter] = useState(initialState?.documentType ?? '');
    const [documentRelationTypeFilter, setDocumentRelationTypeFilter] = useState(
        initialState?.documentRelationType ?? ''
    );
    const [notice, setNotice] = useState('正在连接后端知识空间和图谱服务。');
    const [noticeTone, setNoticeTone] = useState<NoticeTone>('loading');
    const [routeRecoveryError, setRouteRecoveryError] = useState<string | null>(null);
    const [spaces, setSpaces] = useState<KnowledgeSpace[]>([]);
    const [currentSpaceId, setCurrentSpaceId] = useState<string | null>(null);
    const [persistedDocuments, setPersistedDocuments] = useState<SourceDocument[]>([]);
    const [isSpaceFormOpen, setIsSpaceFormOpen] = useState(false);
    const [newSpaceName, setNewSpaceName] = useState('');
    const [newSpaceDescription, setNewSpaceDescription] = useState('');
    const [isManagingSpace, setIsManagingSpace] = useState(false);
    const [isImporting, setIsImporting] = useState(false);
    const [documentPreview, setDocumentPreview] = useState<DocumentPreviewSelection | null>(null);
    const [extractionView, setExtractionView] = useState<AiExtractionViewState | null>(null);
    const [extractionResultLoadErrors, setExtractionResultLoadErrors] = useState<Record<string, string>>({});
    const [deletingDocumentId, setDeletingDocumentId] = useState<string | null>(null);
    const [selectedDocumentIds, setSelectedDocumentIds] = useState<Record<string, boolean>>({});
    const [isBatchDeleting, setIsBatchDeleting] = useState(false);
    const [isBatchExtracting, setIsBatchExtracting] = useState(false);
    const [deleteConfirmation, setDeleteConfirmation] = useState<DeleteConfirmation | null>(null);
    const [aiRelationReviewStatuses, setAiRelationReviewStatuses] = useState<Record<string, AiRelationReviewStatus>>({});
    const [aiRelationReviewSelections, setAiRelationReviewSelections] = useState<AiRelationReviewSelection>({});
    const [documentExtractionStates, setDocumentExtractionStates] = useState<Record<string, DocumentExtractionState>>({});
    const [loadingExtractionResultId, setLoadingExtractionResultId] = useState<string | null>(null);
    const [documentPage, setDocumentPage] = useState(1);
    const [documentSearch, setDocumentSearch] = useState('');
    const [documentSearchQuery, setDocumentSearchQuery] = useState('');
    const [documentTotal, setDocumentTotal] = useState(0);
    const [documentTotalPages, setDocumentTotalPages] = useState(0);
    const [isLoadingDocuments, setIsLoadingDocuments] = useState(false);
    const [isLoadingMoreDocuments, setIsLoadingMoreDocuments] = useState(false);
    const [documentLoadMoreError, setDocumentLoadMoreError] = useState<string | null>(null);
    const [documentRefreshKey, setDocumentRefreshKey] = useState(0);
    const [graphRefreshKey, setGraphRefreshKey] = useState(0);
    const [confirmedTags, setConfirmedTags] = useState<KnowledgeTagSummary[]>([]);
    const [isLoadingConfirmedTags, setIsLoadingConfirmedTags] = useState(false);
    const [confirmedTagLoadError, setConfirmedTagLoadError] = useState<string | null>(null);
    const [tagRefreshKey, setTagRefreshKey] = useState(0);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const preservedDocumentNoticeSpaceIdRef = useRef<string | null>(null);
    const suppressDocumentSearchNoticeRef = useRef(false);
    const documentSearchPendingRef = useRef(false);
    const documentLoadMorePendingRef = useRef(false);
    const queuedBatchExtractionDocumentIdsRef = useRef<Record<string, boolean>>({});
    const graphModeRef = useRef(graphMode);
    graphModeRef.current = graphMode;

    useEffect(() => {
        // 切换知识空间或筛选条件后清空不可见卡片选择，批量操作只作用于当前列表
        setSelectedDocumentIds({});
        documentLoadMorePendingRef.current = false;
        setDocumentLoadMoreError(null);
    }, [currentSpaceId, documentSearchQuery]);

    useEffect(() => {
        // 切换知识空间时不再跟踪上一空间中已受理但尚未开始的批量抽取任务
        queuedBatchExtractionDocumentIdsRef.current = {};
    }, [currentSpaceId]);

    useEffect(() => {
        if (!currentSpaceId) {
            setConfirmedTags([]);
            setConfirmedTagLoadError(null);
            setIsLoadingConfirmedTags(false);
            return;
        }
        let cancelled = false;
        setIsLoadingConfirmedTags(true);
        setConfirmedTagLoadError(null);

        const loadConfirmedTags = async () => {
            try {
                const loadedTags = await listConfirmedKnowledgeTags(currentSpaceId);
                if (cancelled) return;
                setConfirmedTags(loadedTags);
            } catch (error) {
                if (cancelled) return;
                setConfirmedTags([]);
                setConfirmedTagLoadError(error instanceof Error ? error.message : '标签导航加载失败');
            } finally {
                if (!cancelled) setIsLoadingConfirmedTags(false);
            }
        };

        void loadConfirmedTags();
        return () => {
            cancelled = true;
        };
    }, [currentSpaceId, tagRefreshKey]);

    useEffect(() => {
        // 等待用户停止输入后再提交搜索条件，避免每个字符都请求列表接口
        const debounceTimer = window.setTimeout(() => {
            documentSearchPendingRef.current = false;
            const normalizedSearch = documentSearch.trim();
            if (normalizedSearch !== documentSearchQuery) {
                setDocumentSearchQuery(normalizedSearch);
            }
        }, 300);
        return () => window.clearTimeout(debounceTimer);
    }, [documentSearch, documentSearchQuery]);

    useEffect(() => {
        let cancelled = false;

        const loadSpaces = async () => {
            try {
                const loadedSpaces = await listKnowledgeSpaces();
                if (cancelled) return;
                setSpaces(loadedSpaces);
                if (!loadedSpaces.length) {
                    setCurrentSpaceId(null);
                    setPersistedDocuments([]);
                    setDocumentTotal(0);
                    setDocumentTotalPages(0);
                    setDocumentExtractionStates({});
                    setGraph({nodes: [], edges: [], documents: []});
                    setDocumentGraph({nodes: [], edges: []});
                    setSelectedNodeId(null);
                    setNotice('当前还没有知识空间，请先创建一个。');
                    setNoticeTone('warning');
                    return;
                }
                const requestedSpace = initialState?.spaceId
                    ? loadedSpaces.find((space) => space.id === initialState.spaceId)
                    : undefined;
                if (initialState?.spaceId && !requestedSpace) {
                    setRouteRecoveryError('详情链接所属的知识空间不存在或已被移除，已返回当前可用空间。');
                }
                setCurrentSpaceId((current) => current && loadedSpaces.some((space) => space.id === current)
                    ? current
                    : requestedSpace?.id ?? loadedSpaces[0]?.id ?? null);
            } catch (error) {
                if (cancelled) return;
                setNotice(`后端知识空间服务未连接，当前暂不可用：${error instanceof Error ? error.message : '未知错误'}`);
                setNoticeTone('error');
            }
        };

        void loadSpaces();
        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        if (!currentSpaceId) return;
        if (
            documentSearchPendingRef.current
            || (documentSearch !== documentSearchQuery && documentSearch.trim() === documentSearchQuery)
        ) {
            // 搜索词仍在防抖窗口内时，跳过页码归一化触发的中间请求
            return;
        }
        let cancelled = false;
        let pollingErrorVisible = false;
        let pollingTimer: ReturnType<typeof setTimeout> | undefined;
        const abortController = new AbortController();
        const currentPage = documentPage;
        const isAppendingRequest = documentLoadMorePendingRef.current && currentPage > 1;

        const loadPersistedDocuments = async (isPolling = false) => {
            const pagesToLoad = isPolling
                ? Array.from({length: currentPage}, (_, index) => index + 1)
                : isAppendingRequest
                    ? [currentPage]
                    : currentPage > 1
                        ? Array.from({length: currentPage}, (_, index) => index + 1)
                        : [1];
            const shouldPreserveNotice = !isPolling
                && preservedDocumentNoticeSpaceIdRef.current === currentSpaceId;
            const isSearchRequest = suppressDocumentSearchNoticeRef.current;
            if (!isPolling) {
                setIsLoadingDocuments(currentPage === 1);
                setIsLoadingMoreDocuments(isAppendingRequest);
                setDocumentLoadMoreError(null);
            }
            if (!isPolling && !shouldPreserveNotice && !isSearchRequest) {
                setNotice('正在加载当前知识空间的来源资料。');
                setNoticeTone('loading');
            }
            try {
                // 追加时只请求下一页；刷新或轮询时同步当前已经加载的全部页面，保持前面卡片状态可更新
                const responses = await Promise.all(pagesToLoad.map((page) => listSourceDocuments(
                    currentSpaceId,
                    page,
                    DOCUMENT_PAGE_SIZE,
                    documentSearchQuery,
                    abortController.signal
                )));
                if (cancelled) return;
                const response = responses[responses.length - 1];
                if (!response) return;
                const incomingDocuments = responses.flatMap((item) => item.items);

                if (!isPolling && isAppendingRequest && !incomingDocuments.length) {
                    documentLoadMorePendingRef.current = false;
                    if (response.totalPages > 0) {
                        setDocumentPage(response.totalPages);
                    } else {
                        setDocumentPage(1);
                        setPersistedDocuments([]);
                        setDocumentExtractionStates({});
                    }
                    return;
                }

                const shouldReplaceDocuments = !isPolling && !isAppendingRequest && currentPage === 1;
                setPersistedDocuments((current) => shouldReplaceDocuments
                    ? incomingDocuments
                    : mergeDocuments(current, incomingDocuments));
                setDocumentPage(response.page);
                setDocumentTotal(response.total);
                setDocumentTotalPages(response.totalPages);
                setDocumentExtractionStates((current) => {
                    const nextStates = shouldReplaceDocuments ? {} : {...current};
                    incomingDocuments.forEach((document) => {
                        const state = toExtractionState(document);
                        if (state) {
                            delete queuedBatchExtractionDocumentIdsRef.current[document.id];
                            nextStates[document.id] = state;
                        }
                        if (queuedBatchExtractionDocumentIdsRef.current[document.id]) {
                            // 后台线程尚未创建运行记录时，在当前卡片保留已受理状态并继续轮询
                            nextStates[document.id] = current[document.id] ?? {
                                status: 'processing',
                                message: '批量提取已受理，等待服务端执行',
                            };
                        }
                    });
                    return nextStates;
                });
                setGraph((current) => ({
                    ...current,
                    documents: mergeDocuments(
                        current.documents.filter((document) => !document.spaceId),
                        incomingDocuments,
                    ),
                }));
                const loadedDocuments = mergeDocuments(persistedDocuments, incomingDocuments);
                const hasProcessingDocument = loadedDocuments.some(
                    (document) => document.latestExtraction?.status === 'processing'
                ) || loadedDocuments.some(
                    (document) => queuedBatchExtractionDocumentIdsRef.current[document.id]
                );
                if (!isPolling && !shouldPreserveNotice) {
                    setNotice(documentSearchQuery
                        ? response.total
                            ? `已找到 ${response.total} 份匹配的来源资料。`
                            : '未找到匹配的来源资料。'
                        : response.total
                            ? `已加载 ${Math.min(response.total, currentPage * DOCUMENT_PAGE_SIZE)} / ${response.total} 份来源资料。`
                            : '当前知识空间尚未导入来源资料。');
                    setNoticeTone('success');
                } else if (pollingErrorVisible) {
                    pollingErrorVisible = false;
                    setNotice(hasProcessingDocument ? 'AI 提取状态刷新已恢复。' : 'AI 提取状态已更新。');
                    setNoticeTone('success');
                }
                if (!isPolling && shouldPreserveNotice) {
                    // 资料变更或批量任务后的列表刷新只同步分页数据，保留当前操作结果提示
                    preservedDocumentNoticeSpaceIdRef.current = null;
                }
                if (!isPolling && isSearchRequest) {
                    // 名称搜索只在请求完成后更新最终结果，不显示中间加载文案，避免输入时提示框闪烁
                    suppressDocumentSearchNoticeRef.current = false;
                }

                if (hasProcessingDocument) {
                    // 等本次请求完成后再刷新当前已加载页面，避免慢请求重叠并发
                    pollingTimer = setTimeout(
                        () => void loadPersistedDocuments(true),
                        DOCUMENT_PROCESSING_POLL_INTERVAL_MS
                    );
                }
                if (!isPolling) documentLoadMorePendingRef.current = false;
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
                if (currentPage > 1) {
                    documentLoadMorePendingRef.current = false;
                    setDocumentLoadMoreError(error instanceof Error ? error.message : '未知错误');
                    setNotice(`${isAppendingRequest ? '加载更多' : '刷新'}来源资料失败：${error instanceof Error ? error.message : '未知错误'}`);
                    setNoticeTone('warning');
                    return;
                }
                setPersistedDocuments([]);
                setDocumentTotal(0);
                setDocumentTotalPages(0);
                setIsLoadingDocuments(false);
                setDocumentExtractionStates({});
                preservedDocumentNoticeSpaceIdRef.current = null;
                // 搜索请求不显示中间加载态，但最终失败仍保留一次稳定错误提示
                suppressDocumentSearchNoticeRef.current = false;
                setNotice(`来源资料加载失败：${error instanceof Error ? error.message : '未知错误'}`);
                setNoticeTone('error');
            } finally {
                if (!cancelled && !isPolling) {
                    setIsLoadingDocuments(false);
                    setIsLoadingMoreDocuments(false);
                }
            }
        };

        void loadPersistedDocuments();
        return () => {
            cancelled = true;
            abortController.abort();
            if (pollingTimer) clearTimeout(pollingTimer);
        };
    }, [currentSpaceId, documentPage, documentSearch, documentSearchQuery, documentRefreshKey]);

    useEffect(() => {
        if (!currentSpaceId) return;
        let cancelled = false;

        const loadGraph = async () => {
            try {
                // 查询当前知识空间图谱，候选关系和审核状态均来自服务端
                const loadedGraph = await getGraph(currentSpaceId);
                if (cancelled) return;
                setGraph((current) => ({...loadedGraph, documents: current.documents}));
                if (graphModeRef.current === 'entity') {
                    setSelectedNodeId((current) => loadedGraph.nodes.some((node) => node.id === current)
                        ? current
                        : loadedGraph.nodes[0]?.id ?? null);
                }
            } catch (error) {
                if (cancelled) return;
                setNotice(`图谱加载失败，当前暂无法展示图谱：${error instanceof Error ? error.message : '未知错误'}`);
                setNoticeTone('warning');
            }
        };

        void loadGraph();
        return () => {
            cancelled = true;
        };
    }, [currentSpaceId, graphRefreshKey]);

    useEffect(() => {
        if (!currentSpaceId) {
            setDocumentGraph({nodes: [], edges: []});
            setLoadedDocumentGraphSpaceId(null);
            return;
        }
        let cancelled = false;
        setLoadedDocumentGraphSpaceId(null);

        const loadDocumentGraph = async () => {
            try {
                // 查询独立文档关系图，节点来自 source_documents，边来自 confirmed document_relations
                const loadedDocumentGraph = await getDocumentGraph(currentSpaceId);
                if (cancelled) return;
                setDocumentGraph(loadedDocumentGraph);
                setLoadedDocumentGraphSpaceId(currentSpaceId);
                if (graphModeRef.current === 'document') {
                    setSelectedNodeId((current) => loadedDocumentGraph.nodes.some((node) => node.id === current)
                        ? current
                        : loadedDocumentGraph.nodes[0]?.id ?? null);
                }
            } catch (error) {
                if (cancelled) return;
                setDocumentGraph({nodes: [], edges: []});
                setNotice(`文档关系图加载失败：${error instanceof Error ? error.message : '未知错误'}`);
                setNoticeTone('warning');
            }
        };

        void loadDocumentGraph();
        return () => {
            cancelled = true;
        };
    }, [currentSpaceId, graphRefreshKey]);

    const currentSpace = spaces.find((space) => space.id === currentSpaceId) ?? null;

    const documentTypeOptions = useMemo(() => Array.from(new Set(
        documentGraph.nodes.map((node) => node.documentType)
    )).sort(), [documentGraph.nodes]);
    const documentRelationTypeOptions = useMemo(() => Array.from(new Set(
        documentGraph.edges.map((edge) => edge.relationType)
    )).sort(), [documentGraph.edges]);
    const formatDocumentGraphEdgeType = useCallback(
        (relationType: string) => documentRelationTypeLabels[relationType] ?? relationType,
        []
    );
    const hasDocumentGraphFilters = Boolean(documentTypeFilter || documentRelationTypeFilter);
    const filteredDocumentGraph = useMemo<DocumentGraphData>(() => {
        if (!hasDocumentGraphFilters) return documentGraph;

        const documentTypeMatchedNodes = documentGraph.nodes.filter((node) => !documentTypeFilter
            || node.documentType === documentTypeFilter);
        const documentTypeMatchedNodeIds = new Set(documentTypeMatchedNodes.map((node) => node.id));
        const filteredEdges = documentGraph.edges.filter((edge) => (
            (!documentRelationTypeFilter || edge.relationType === documentRelationTypeFilter)
            && documentTypeMatchedNodeIds.has(edge.sourceDocumentId)
            && documentTypeMatchedNodeIds.has(edge.targetDocumentId)
        ));
        const connectedNodeIds = new Set(filteredEdges.flatMap((edge) => [
            edge.sourceDocumentId,
            edge.targetDocumentId,
        ]));

        return {
            nodes: documentTypeMatchedNodes.filter((node) => connectedNodeIds.has(node.id)),
            edges: filteredEdges,
        };
    }, [documentGraph, documentRelationTypeFilter, documentTypeFilter, hasDocumentGraphFilters]);

    useEffect(() => {
        if (graphMode !== 'document') return;
        setSelectedNodeId((current) => filteredDocumentGraph.nodes.some((node) => node.id === current)
            ? current
            : filteredDocumentGraph.nodes[0]?.id ?? null);
    }, [filteredDocumentGraph.nodes, graphMode]);

    const documentGraphAsGraphData: GraphData = useMemo(() => ({
        nodes: filteredDocumentGraph.nodes.map((node) => ({
            id: node.id,
            type: 'document',
            label: node.name,
            summary: node.summary,
            status: node.status === 'active' ? 'active' : 'orphan',
            sourceIds: [node.id],
            createdAt: node.updatedAt,
            updatedAt: node.updatedAt,
        })),
        edges: filteredDocumentGraph.edges.map((edge) => ({
            id: edge.id,
            source: edge.sourceDocumentId,
            target: edge.targetDocumentId,
            type: edge.relationType,
            status: edge.status,
            confidence: edge.confidence,
            evidence: [],
            createdAt: edge.updatedAt,
            updatedAt: edge.updatedAt,
        })),
        documents: filteredDocumentGraph.nodes.map((node) => ({
            id: node.id,
            name: node.name,
            kind: node.kind,
            documentType: node.documentType as SourceDocument['documentType'],
            contentHash: '',
            excerpt: node.summary,
            status: node.status,
            importedAt: node.updatedAt,
            updatedAt: node.updatedAt,
        })),
    }), [filteredDocumentGraph]);
    const activeGraph = graphMode === 'document' ? documentGraphAsGraphData : graph;
    const selectedNode = graphMode === 'entity'
        ? graph.nodes.find((node) => node.id === selectedNodeId) ?? null
        : null;
    const selectedEdges = graph.edges.filter((edge) => edge.source === selectedNodeId || edge.target === selectedNodeId);

    const showDocumentGraphDocument = useCallback((documentId: string, evidence?: DocumentGraphEvidence) => {
        const node = documentGraph.nodes.find((item) => item.id === documentId);
        if (!node) return;
        setDocumentPreview({
            document: {
                id: node.id,
                name: node.name,
                kind: node.kind,
                documentType: node.documentType as SourceDocument['documentType'],
                contentHash: '',
                excerpt: node.summary,
                status: node.status,
                importedAt: node.updatedAt,
                updatedAt: node.updatedAt,
            },
            initialTab: evidence ? 'source' : 'rendered',
            evidence,
        });
    }, [documentGraph.nodes]);

    const openDocumentGraphDocument = useCallback((documentId: string, evidence?: DocumentGraphEvidence) => {
        if (!currentSpaceId) return;
        const query = new URLSearchParams();
        if (search.trim()) query.set('graphSearch', search.trim());
        if (documentTypeFilter) query.set('documentType', documentTypeFilter);
        if (documentRelationTypeFilter) query.set('documentRelationType', documentRelationTypeFilter);
        if (evidence) query.set('evidenceId', evidence.id);
        const suffix = query.size ? `?${query.toString()}` : '';

        // 将文档详情写入可复制和可刷新的 URL，路由页继续复用当前工作台与详情弹窗
        router.push(`/spaces/${encodeURIComponent(currentSpaceId)}/documents/${encodeURIComponent(documentId)}${suffix}`);
    }, [currentSpaceId, documentRelationTypeFilter, documentTypeFilter, router, search]);

    useEffect(() => {
        if (
            !initialState?.documentId
            || !currentSpaceId
            || currentSpaceId !== initialState.spaceId
            || loadedDocumentGraphSpaceId !== currentSpaceId
        ) {
            return;
        }
        const targetNode = documentGraph.nodes.find((node) => node.id === initialState.documentId);
        if (!targetNode) {
            setDocumentPreview(null);
            setRouteRecoveryError('详情链接指向的来源资料不存在、已失效或不属于当前知识空间。');
            return;
        }
        const targetEvidence = initialState.evidenceId
            ? documentGraph.edges
                .flatMap((edge) => edge.evidences)
                .find((evidence) => evidence.id === initialState.evidenceId
                    && evidence.sourceDocumentId === initialState.documentId)
            : undefined;
        setRouteRecoveryError(null);
        setView('graph');
        setGraphMode('document');
        setSelectedNodeId(initialState.documentId);

        // 从详情路由恢复来源资料弹窗，并在证据仍有效时继续定位原文
        showDocumentGraphDocument(initialState.documentId, targetEvidence);
    }, [
        currentSpaceId,
        documentGraph.edges,
        documentGraph.nodes,
        initialState,
        loadedDocumentGraphSpaceId,
        showDocumentGraphDocument,
    ]);

    const closeDocumentPreview = useCallback(() => {
        if (initialState?.documentId && currentSpaceId) {
            const query = new URLSearchParams({
                spaceId: currentSpaceId,
                graphMode: 'document',
                selectedNodeId: initialState.documentId,
            });
            if (search.trim()) query.set('graphSearch', search.trim());
            if (documentTypeFilter) query.set('documentType', documentTypeFilter);
            if (documentRelationTypeFilter) query.set('documentRelationType', documentRelationTypeFilter);

            // 关闭可恢复详情时返回文档关系图，并保留当前空间、搜索词和选中节点
            router.replace(`/?${query.toString()}`);
            return;
        }
        const closingDocumentId = documentPreview?.document.id;
        setDocumentPreview(null);
        setExtractionView((current) => current && current.documentId === closingDocumentId
            && current.status !== 'connecting'
            && current.status !== 'processing'
            ? null
            : current);
    }, [
        currentSpaceId,
        documentPreview?.document.id,
        documentRelationTypeFilter,
        documentTypeFilter,
        initialState?.documentId,
        router,
        search,
    ]);

    const visibleNodes = useMemo(() => {
        const keyword = search.trim().toLowerCase();
        return activeGraph.nodes.filter((node) => !keyword || `${node.label} ${node.summary}`.toLowerCase().includes(keyword));
    }, [activeGraph.nodes, search]);

    const visibleEdges = useMemo(() => {
        const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
        return activeGraph.edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target) && edge.status !== 'rejected');
    }, [activeGraph.edges, visibleNodes]);
    const confirmedEdgeCount = activeGraph.edges.filter((edge) => edge.status === 'confirmed').length;

    const loadMoreDocuments = () => {
        if (
            documentLoadMorePendingRef.current
            || isLoadingDocuments
            || isLoadingMoreDocuments
            || documentLoadMoreError
            || documentPage >= documentTotalPages
        ) {
            return;
        }
        documentLoadMorePendingRef.current = true;
        setDocumentPage((current) => current + 1);
    };

    const retryLoadMoreDocuments = () => {
        if (!documentLoadMoreError || documentLoadMorePendingRef.current) return;
        setDocumentLoadMoreError(null);
        documentLoadMorePendingRef.current = true;
        setDocumentRefreshKey((current) => current + 1);
    };

    const reviewAiRelations = async (
        extractionId: string,
        documentId: string,
        decisions: AiRelationReviewDecision[]
    ) => {
        if (!currentSpaceId || !decisions.length) return;
        try {
            setNotice(`正在保存 ${decisions.length} 条关联审核结果…`);
            setNoticeTone('loading');
            // 将审核决定提交给服务端，由服务端按抽取结果校验主体、客体和证据
            const response = await reviewDocumentExtractionRelations(
                currentSpaceId,
                documentId,
                extractionId,
                decisions.map(({chunkId, relationIndex, action}) => ({chunkId, relationIndex, action}))
            );
            setAiRelationReviewStatuses((current) => decisions.reduce((next, decision) => ({
                ...next,
                [getAiRelationReviewKey(extractionId, decision.relationKey)]: decision.action === 'ACCEPT' ? 'accepted' : 'rejected',
            }), {...current}));
            setAiRelationReviewSelections((current) => {
                const next = {...current};
                decisions.forEach((decision) => delete next[getAiRelationReviewKey(extractionId, decision.relationKey)]);
                return next;
            });
            setGraphRefreshKey((current) => current + 1);
            setNotice(response.pendingCount
                ? `已保存 ${decisions.length} 条审核结果，当前抽取还剩 ${response.pendingCount} 条待审核关联。`
                : '本次 AI 抽取的关联审核已完成，图谱已更新。');
            setNoticeTone('success');
        } catch (error) {
            setNotice(`关联审核保存失败，未改变当前卡片状态：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('error');
        }
    };

    const updateAiRelationReviewSelection = (relationKey: string, selected: boolean) => {
        setAiRelationReviewSelections((current) => ({
            ...current,
            [relationKey]: selected,
        }));
    };

    const hydrateAiRelationReviewStatuses = async (
        spaceId: string,
        documentId: string,
        extractionId: string
    ) => {
        try {
            // 刷新或重新打开历史结果时恢复服务端已经保存的审核决定
            const states = await listDocumentExtractionReviewStates(spaceId, documentId, extractionId);
            setAiRelationReviewStatuses((current) => states.reduce((next, state) => ({
                ...next,
                [getAiRelationReviewKey(extractionId, `${state.chunkId}-relation-${state.relationIndex}`)]: state.action === 'ACCEPT' ? 'accepted' : 'rejected',
            }), {...current}));
        } catch (error) {
            setNotice(`历史审核状态恢复失败：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('warning');
        }
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
        setIsManagingSpace(true);
        try {
            await deleteKnowledgeSpace(space.id);
            const remainingSpaces = spaces.filter((item) => item.id !== space.id);
            setSpaces(remainingSpaces);
            setDocumentPage(1);
            setCurrentSpaceId(remainingSpaces[0]?.id ?? null);
            if (!remainingSpaces.length) {
                setPersistedDocuments([]);
                setDocumentTotal(0);
                setDocumentTotalPages(0);
                setDocumentExtractionStates({});
                setGraph({nodes: [], edges: [], documents: []});
                setSelectedNodeId(null);
            }
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
            setNotice(`导入失败，未能保存来源资料：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('error');
        } finally {
            setIsImporting(false);
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    };

    const removeDocument = async (document: SourceDocument) => {
        if (!currentSpaceId || isBatchDeleting) return;
        setDeletingDocumentId(document.id);
        try {
            await deleteSourceDocument(currentSpaceId, document.id);
            // 删除后回到列表起点重新加载，避免追加列表保留已经失效的卡片顺序
            preservedDocumentNoticeSpaceIdRef.current = currentSpaceId;
            setDocumentPage(1);
            setDocumentRefreshKey((current) => current + 1);
            setDocumentExtractionStates((current) => {
                const nextStates = {...current};
                delete nextStates[document.id];
                return nextStates;
            });
            setSelectedDocumentIds((current) => {
                const next = {...current};
                delete next[document.id];
                return next;
            });
            setDocumentPreview((current) => current?.document.id === document.id ? null : current);
            setNotice(`来源资料“${document.name}”已删除，相关图谱来源贡献已同步更新。`);
            setNoticeTone('success');
        } catch (error) {
            setNotice(`删除来源资料失败：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('error');
        } finally {
            setDeletingDocumentId(null);
        }
    };

    const removeDocuments = async (documents: SourceDocument[]) => {
        if (!currentSpaceId || !documents.length || isBatchDeleting) return;
        setIsBatchDeleting(true);
        setNotice(`正在删除 ${documents.length} 份来源资料…`);
        setNoticeTone('loading');

        try {
            // 通过一次后端批量接口在事务中软删除资料并同步失效图谱来源贡献
            const response = await deleteSourceDocuments(
                currentSpaceId,
                documents.map((document) => document.id)
            );
            const deletedDocumentIds = new Set(response.documentIds);
            const deletedDocuments = documents.filter((document) => deletedDocumentIds.has(document.id));
            // 后端事务完成后只刷新一次列表，并回到列表起点清理已删除卡片
            preservedDocumentNoticeSpaceIdRef.current = currentSpaceId;
            setDocumentPage(1);
            setDocumentRefreshKey((current) => current + 1);
            setDocumentExtractionStates((current) => {
                const nextStates = {...current};
                deletedDocuments.forEach((document) => delete nextStates[document.id]);
                return nextStates;
            });
            setSelectedDocumentIds((current) => {
                const next = {...current};
                deletedDocuments.forEach((document) => delete next[document.id]);
                return next;
            });
            deletedDocuments.forEach((document) => delete queuedBatchExtractionDocumentIdsRef.current[document.id]);
            setDocumentPreview((current) => current && deletedDocuments.some((document) => document.id === current.document.id)
                ? null
                : current);
            setNotice(`批量删除完成：已删除 ${response.deletedCount} 份来源资料，相关图谱来源贡献已同步更新。`);
            setNoticeTone('success');
        } catch (error) {
            setNotice(`批量删除来源资料失败：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('error');
        } finally {
            setIsBatchDeleting(false);
        }
    };

    const extractDocuments = async (documents: SourceDocument[]) => {
        if (!currentSpaceId || !documents.length || isBatchExtracting) return;
        setIsBatchExtracting(true);
        setNotice(`正在向服务端提交 ${documents.length} 份来源资料的批量 AI 提取任务…`);
        setNoticeTone('loading');

        try {
            // 只发起一次批量接口，由后端有界线程池并发执行每份资料的独立抽取任务
            const response = await submitDocumentExtractionBatch(
                currentSpaceId,
                documents.map((document) => document.id)
            );
            response.documentIds.forEach((documentId) => {
                queuedBatchExtractionDocumentIdsRef.current[documentId] = true;
            });
            setDocumentExtractionStates((current) => ({
                ...current,
                ...Object.fromEntries(response.documentIds.map((documentId) => [documentId, {
                    status: 'processing' as const,
                    message: '批量提取已受理，等待服务端执行',
                }])),
            }));
            setSelectedDocumentIds((current) => {
                const next = {...current};
                response.documentIds.forEach((documentId) => delete next[documentId]);
                return next;
            });
            // 立即刷新当前页，并持续轮询已受理但尚未创建运行记录的后台任务
            preservedDocumentNoticeSpaceIdRef.current = currentSpaceId;
            setDocumentRefreshKey((current) => current + 1);
            if (!response.acceptedCount) {
                setNotice('批量 AI 提取未被服务端受理，请稍后重试。');
                setNoticeTone('warning');
                return;
            }

            const rejectedDocumentNames = documents
                .filter((document) => response.rejectedDocumentIds.includes(document.id))
                .map((document) => document.name);
            const summary = `批量 AI 提取已受理 ${response.acceptedCount} / ${response.requestedCount} 份资料。`;
            setNotice(rejectedDocumentNames.length
                ? `${summary} 以下资料未受理：${rejectedDocumentNames.join('、')}。`
                : `${summary} 请留在来源资料页查看每份资料的独立状态和结果。`);
            setNoticeTone(rejectedDocumentNames.length ? 'warning' : 'success');
        } catch (error) {
            setNotice(`批量 AI 提取提交失败：${error instanceof Error ? error.message : '未知错误'}`);
            setNoticeTone('error');
        } finally {
            setIsBatchExtracting(false);
        }
    };

    const extractDocument = async (document: SourceDocument) => {
        if (!currentSpaceId) return;

        // 在同一来源资料弹窗中切换到 AI 输出，后续实时事件和审核结果不再使用独立弹窗
        setDocumentPreview({document, initialTab: 'ai'});
        setExtractionResultLoadErrors((current) => {
            const next = {...current};
            delete next[document.id];
            return next;
        });
        setExtractionView({
            documentId: document.id,
            documentName: document.name,
            status: 'connecting',
            currentChunkIndex: 0,
            chunkCount: 0,
            chunks: [],
            rawOutput: '',
            message: '正在建立 AI 抽取事件流',
        });
        setDocumentExtractionStates((current) => ({
            ...current,
            [document.id]: {status: 'processing', message: '正在建立 AI 抽取事件流'},
        }));
        try {
            await streamDocumentExtraction(currentSpaceId, document.id, {
                onRunStarted: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'processing',
                        extractionId: event.extractionRunId,
                        provider: event.provider,
                        model: event.model,
                        promptVersion: event.promptVersion,
                        schemaVersion: event.schemaVersion,
                        message: '抽取运行已创建，正在解析来源资料',
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'processing',
                            extractionId: event.extractionRunId,
                            message: '抽取运行已创建，正在解析来源资料',
                        },
                    }));
                },
                onChunkStarted: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'processing',
                        extractionId: event.extractionRunId,
                        currentChunkId: event.chunkId,
                        currentSectionPath: event.sectionPath,
                        currentChunkIndex: event.chunkIndex,
                        chunkCount: event.chunkCount,
                        rawOutput: '',
                        message: `正在处理第 ${event.chunkIndex} / ${event.chunkCount} 个分片`,
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'processing',
                            extractionId: event.extractionRunId,
                            message: `正在处理第 ${event.chunkIndex} / ${event.chunkCount} 个分片`,
                        },
                    }));
                },
                onDelta: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        extractionId: event.extractionRunId,
                        currentChunkId: event.chunkId,
                        currentSectionPath: event.sectionPath,
                        rawOutput: current.rawOutput + event.delta,
                    } : current);
                },
                onChunkCompleted: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        extractionId: event.extractionRunId,
                        chunkCount: event.chunkCount,
                        chunks: [
                            ...current.chunks.filter((chunk) => chunk.chunkId !== event.chunk.chunkId),
                            event.chunk,
                        ],
                        message: `第 ${event.chunkIndex} / ${event.chunkCount} 个分片已通过结构和证据校验`,
                    } : current);
                },
                onDocumentSummaryStarted: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'processing',
                        extractionId: event.extractionRunId,
                        currentChunkIndex: event.chunkCount,
                        chunkCount: event.chunkCount,
                        message: '正在生成全文摘要',
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'processing',
                            extractionId: event.extractionRunId,
                            message: '正在生成全文摘要',
                        },
                    }));
                },
                onDocumentSummaryCompleted: (event) => {
                    const message = event.status === 'completed'
                        ? '全文摘要已生成，正在保存完整结果'
                        : event.errorMessage || '全文摘要生成失败，已保留候选事实';
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'processing',
                        extractionId: event.extractionRunId,
                        message,
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'processing',
                            extractionId: event.extractionRunId,
                            message,
                        },
                    }));
                },
                onCompleted: (event) => {
                    const entityCount = event.result.chunks.reduce((count, chunk) => count + chunk.extraction.entities.length, 0);
                    const relationCount = event.result.chunks.reduce((count, chunk) => count + chunk.extraction.relations.length, 0);
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'completed',
                        extractionId: event.extractionRunId,
                        currentChunkIndex: event.result.chunkCount,
                        chunkCount: event.result.chunkCount,
                        chunks: event.result.chunks,
                        message: event.result.summary
                            ? '完整结果和全文摘要已通过校验并保存'
                            : '候选结果已通过校验并保存，全文摘要生成失败',
                        result: event.result,
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'success',
                            extractionId: event.extractionRunId,
                            message: `${event.result.chunkCount} 个分片，${entityCount} 个候选实体，${relationCount} 条候选关系`,
                        },
                    }));
                    // AI 抽取完成后在同一个弹窗内开始人工关联审核，保留候选和证据上下文
                    setNotice(`AI 抽取完成，请在当前弹窗审核 ${relationCount} 条候选关系。`);
                    setNoticeTone('success');
                    // 候选关系已经以 suggested 写入服务端，立即刷新图谱显示待审核虚线关系
                    setGraphRefreshKey((current) => current + 1);
                    void hydrateAiRelationReviewStatuses(currentSpaceId, document.id, event.extractionRunId);
                    // 重新读取当前页，让成功运行保存的 AI 摘要替换资料卡片原始预览
                    setDocumentRefreshKey((current) => current + 1);
                },
                onError: (event) => {
                    setExtractionView((current) => current?.documentId === document.id ? {
                        ...current,
                        status: 'error',
                        extractionId: event.extractionRunId,
                        currentChunkId: event.chunkId ?? current.currentChunkId,
                        message: event.message,
                    } : current);
                    setDocumentExtractionStates((current) => ({
                        ...current,
                        [document.id]: {
                            status: 'error',
                            extractionId: event.extractionRunId,
                            message: event.message,
                        },
                    }));
                    // 重新读取当前页，恢复服务端已经持久化的失败状态和历史成功结果
                    setDocumentRefreshKey((current) => current + 1);
                },
            });
        } catch (error) {
            const message = error instanceof Error ? error.message : 'AI 抽取流连接异常中断';
            setExtractionView((current) => current?.documentId === document.id ? {
                ...current,
                status: 'error',
                message,
            } : current);
            setDocumentExtractionStates((current) => ({
                ...current,
                [document.id]: {
                    status: 'error',
                    message,
                },
            }));
        }
    };

    const viewExtractionResult = async (document: SourceDocument) => {
        if (!currentSpaceId) return;

        // 先打开同一来源资料弹窗的 AI 输出页，历史结果读取期间保留明确加载状态
        setDocumentPreview({document, initialTab: 'ai'});
        setLoadingExtractionResultId(document.id);
        setExtractionResultLoadErrors((current) => {
            const next = {...current};
            delete next[document.id];
            return next;
        });
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
            setExtractionView({
                documentId: document.id,
                documentName: document.name,
                status: 'completed',
                extractionId: detail.result.extractionId,
                provider: detail.result.provider,
                model: detail.result.model,
                promptVersion: detail.result.promptVersion,
                schemaVersion: detail.result.schemaVersion,
                currentChunkIndex: detail.result.chunkCount,
                chunkCount: detail.result.chunkCount,
                chunks: detail.result.chunks,
                rawOutput: '',
                message: '已恢复服务端保存的完整抽取结果',
                result: detail.result,
            });
            void hydrateAiRelationReviewStatuses(currentSpaceId, document.id, detail.result.extractionId);
        } catch (error) {
            const message = error instanceof Error ? error.message : '历史结果加载失败';
            // 历史结果读取失败不等于 AI 抽取失败，单独保留加载错误以免污染卡片运行状态
            setExtractionResultLoadErrors((current) => ({...current, [document.id]: message}));
            setNotice(`AI 历史结果加载失败：${message}`);
            setNoticeTone('error');
        } finally {
            setLoadingExtractionResultId(null);
        }
    };

    const pageTitle = view === 'graph'
        ? '工作图谱'
        : view === 'documents'
            ? '来源资料'
            : '知识健康检查';
    const pageDescription = view === 'graph'
        ? currentSpaceId ? '从项目、任务、人员和资料之间的关系中找到工作上下文。' : '先创建知识空间，再导入文档并生成图谱。'
        : view === 'documents'
            ? currentSpaceId ? '查看当前知识空间中已保存的来源文件、解析状态和文本预览。' : '先创建知识空间，再管理来源资料。'
            : currentSpaceId ? '先处理会影响知识可信度的问题，再继续扩展图谱。' : '创建知识空间后，这里会显示知识健康检查结果。';

    return (
        <main className="app-shell">
            <header className="topbar">
                <div className="brand-lockup">
                    <div className="brand-mark"><GitBranch size={19}/></div>
                    <div>
                        <div className="brand-name">知脉</div>
                        <div className="brand-subtitle">AI 工作知识图谱维护助手</div>
                    </div>
                </div>
                <div className="topbar-actions">
                    <button className="primary-button" disabled={isImporting || !currentSpaceId}
                        onClick={() => fileInputRef.current?.click()}>{isImporting ?
                        <LoaderCircle className="spin" size={16}/> :
                        <Upload size={16}/>} {isImporting ? '正在导入' : '导入资料'}</button>
                    <input ref={fileInputRef} className="hidden-input" type="file" multiple
                        accept=".md,.markdown,.txt,.pdf,application/pdf"
                        onChange={(event) => void importFiles(event.target.files)}/>
                </div>
            </header>

            <div className="workspace-grid">
                <aside className="sidebar">
                    <section className="space-card">
                        <div className="eyebrow">当前知识空间</div>
                        {spaces.length ? <>
                            <div className="space-switcher">
                                <SelectMenu
                                    ariaLabel="选择知识空间"
                                    className="space-select-menu"
                                    value={currentSpaceId ?? ''}
                                    options={spaces.map((space) => ({value: space.id, label: space.name}))}
                                    onChange={(spaceId) => {
                                        suppressDocumentSearchNoticeRef.current = false;
                                        documentSearchPendingRef.current = false;
                                        setDocumentPage(1);
                                        setCurrentSpaceId(spaceId);
                                    }}
                                    disabled={isManagingSpace}
                                />
                                <button className="space-icon-button" aria-label="新建知识空间" title="新建知识空间"
                                    onClick={() => setIsSpaceFormOpen((current) => !current)}><FolderPlus size={15}/>
                                </button>
                                <button className="space-icon-button danger" aria-label="删除当前知识空间"
                                    title="删除当前知识空间"
                                    disabled={!currentSpace || isManagingSpace}
                                    onClick={() => currentSpace && setDeleteConfirmation({
                                        kind: 'space',
                                        item: currentSpace
                                    })}><Trash2 size={15}/></button>
                            </div>
                            <div className="space-title"><span className="space-icon"><Archive
                                size={17}/></span>{currentSpace?.name}</div>
                            <p>{currentSpace?.description ?? '每个知识空间使用独立目录保存来源资料。'}</p>
                        </> : <div className="space-empty-state">
                            <span className="space-icon"><Archive size={17}/></span>
                            <strong>尚未创建知识空间</strong>
                            <p>创建后才能导入文档、执行 AI 抽取和生成图谱。</p>
                        </div>}
                    </section>

                    <nav className="side-nav" aria-label="主导航">
                        <button className={view === 'graph' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('graph')}><LayoutDashboard
                            size={17}/> 工作图谱 <span>{activeGraph.nodes.length}</span></button>
                        <button className={view === 'documents' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('documents')}><FileText
                            size={17}/> 来源资料 <span>{documentTotal}</span></button>
                        <button className={view === 'health' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('health')}><ShieldCheck size={17}/> 知识健康 <span
                            className="warning-count">{issueCount(graph)}</span></button>
                    </nav>

                    <section className="sidebar-section">
                        <div className="section-heading">待处理</div>
                        <div className="work-queue-list">
                            <button className="queue-row" onClick={() => setView('health')}>
                                <span className="queue-row-label"><ShieldCheck size={14}/>待审核关联</span>
                                <span>{graph.edges.filter((edge) => edge.status === 'suggested').length}</span>
                            </button>
                            <button className="queue-row" onClick={() => setView('documents')}>
                                <span className="queue-row-label"><Sparkles size={14}/>已加载失败</span>
                                <span>{persistedDocuments.filter((document) => document.latestExtraction?.status === 'failed').length}</span>
                            </button>
                        </div>
                    </section>

                    <section className="sidebar-section">
                        <div className="section-heading">标签</div>
                        {isLoadingConfirmedTags
                            ? <div className="tag-navigation-empty"><LoaderCircle className="spin" size={15}/>
                                <span>正在读取已确认标签…</span></div>
                            : confirmedTagLoadError
                                ? <div className="tag-navigation-error"><AlertTriangle size={15}/>
                                    <span>标签加载失败：{confirmedTagLoadError}</span>
                                    <button type="button" onClick={() => setTagRefreshKey((current) => current + 1)}>重试</button>
                                </div>
                                : confirmedTags.length
                                    ? <div className="tag-navigation-list">{confirmedTags.map((tag) => <div
                                        className="tag-navigation-row"
                                        key={tag.tagId}
                                        title={`${tag.name} · ${tag.documentCount} 份有效来源资料`}
                                    >
                                        <span><Tags size={13}/>{tag.name}</span>
                                        <strong>{tag.documentCount}</strong>
                                    </div>)}</div>
                                    : <div className="tag-navigation-empty">
                                        <Inbox size={15}/>
                                        <span>完成 AI 分析并确认标签后，这里会显示文档标签。</span>
                                    </div>}
                    </section>

                    <div className="sidebar-footer"><CircleHelp size={15}/> 关联建议必须有证据，并经过人工审核</div>
                </aside>

                <section className="content-area">
                    <div className="page-heading">
                        <div>
                            <div className="eyebrow">工作台 / {currentSpace?.name ?? '未创建知识空间'}</div>
                            <h1>{pageTitle}</h1><p>{pageDescription}</p></div>
                        <div className="page-stats">
                            <div><strong>{activeGraph.nodes.length}</strong><span>{graphMode === 'document' ? '文档节点' : '图谱节点'}</span></div>
                            <div><strong>{confirmedEdgeCount}</strong><span>已采纳关系</span></div>
                            <div><strong>{documentTotal}</strong><span>来源资料</span></div>
                        </div>
                    </div>

                    {notice && <div className={`notice ${noticeTone}`}>{noticeTone === 'loading' ?
                        <LoaderCircle className="spin" size={16}/> : noticeTone === 'error' ?
                            <AlertTriangle size={16}/> : <Check size={16}/>} {notice}
                        <button onClick={() => setNotice('')} aria-label="关闭提示"><X size={15}/></button>
                    </div>}

                    {routeRecoveryError && <div className="notice error" role="alert">
                        <AlertTriangle size={16}/> {routeRecoveryError}
                        <button onClick={() => setRouteRecoveryError(null)} aria-label="关闭详情链接错误提示"><X size={15}/></button>
                    </div>}

                    {!currentSpaceId ? <div className="state-card workspace-empty-state">
                        <Archive size={30}/>
                        <h3>尚未创建知识空间</h3>
                        <p>请在主界面创建知识空间，再导入文档。</p>
                        <button className="primary-button" type="button"
                            onClick={() => setIsSpaceFormOpen(true)}><FolderPlus size={14}/> 创建知识空间</button>
                    </div> : <>
                    {view === 'graph' && <>
                        <div className="toolbar-card">
                            <div className="toolbar-actions">
                                <button className={graphMode === 'entity' ? 'secondary-button selected' : 'secondary-button'}
                                    type="button" onClick={() => {
                                        setGraphMode('entity');
                                        setSelectedNodeId(graph.nodes[0]?.id ?? null);
                                    }}>实体兼容图谱</button>
                                <button className={graphMode === 'document' ? 'secondary-button selected' : 'secondary-button'}
                                    type="button" onClick={() => {
                                        setGraphMode('document');
                                        setSelectedNodeId(documentGraph.nodes[0]?.id ?? null);
                                    }}>文档关系图</button>
                            </div>
                            {graphMode === 'document' && <div className="document-graph-filters" aria-label="文档关系图筛选">
                                <label className="document-graph-filter">文档类型
                                    <SelectMenu
                                        ariaLabel="文档类型"
                                        className="document-graph-select-menu"
                                        value={documentTypeFilter}
                                        options={[
                                            {value: '', label: '全部'},
                                            ...documentTypeOptions.map((documentType) => ({
                                                value: documentType,
                                                label: documentTypeLabels[documentType] ?? documentType
                                            }))
                                        ]}
                                        onChange={setDocumentTypeFilter}
                                    />
                                </label>
                                <label className="document-graph-filter">关系类型
                                    <SelectMenu
                                        ariaLabel="关系类型"
                                        className="document-graph-select-menu"
                                        value={documentRelationTypeFilter}
                                        options={[
                                            {value: '', label: '全部'},
                                            ...documentRelationTypeOptions.map((relationType) => ({
                                                value: relationType,
                                                label: documentRelationTypeLabels[relationType] ?? relationType
                                            }))
                                        ]}
                                        onChange={setDocumentRelationTypeFilter}
                                    />
                                </label>
                                {hasDocumentGraphFilters && <button className="document-graph-filter-reset" type="button"
                                    onClick={() => {
                                        setDocumentTypeFilter('');
                                        setDocumentRelationTypeFilter('');
                                    }} aria-label="清除文档关系图筛选" title="清除筛选"><FilterX size={15}/></button>}
                            </div>}
                            <div className="search-box"><Search size={17}/><input value={search}
                                onChange={(event) => setSearch(event.target.value)}
                                placeholder={graphMode === 'document' ? '搜索来源文档' : '搜索项目、任务、人员或资料'}/></div>
                            <span className="toolbar-hint">{graphMode === 'document'
                                ? '节点来自真实来源资料；实线表示已确认文档关系'
                                : '实线：已采纳　虚线：待审核　红框：需关注'}</span>
                        </div>
                        <div className="graph-card">{graphMode === 'document' && !visibleNodes.length
                            ? <div className="graph-empty-state"><FileText size={28}/><strong>{hasDocumentGraphFilters
                                ? '没有符合当前筛选条件的文档关系'
                                : '当前还没有可展示的文档关系图'}</strong>
                                <span>{hasDocumentGraphFilters
                                    ? '请调整文档类型、关系类型或搜索词；筛选结果只保留仍由确认关系连接的文档。'
                                    : '需要先导入来源文档，并在文档关联审核后确认关系；待审核关系不会出现在默认图中。'}</span></div>
                            : <GraphCanvas nodes={visibleNodes} edges={visibleEdges}
                                selectedNodeId={selectedNodeId} onSelectNode={setSelectedNodeId}
                                ariaLabel={graphMode === 'document' ? '独立文档关系图' : '工作知识关系图谱'}
                                formatEdgeType={graphMode === 'document'
                                    ? formatDocumentGraphEdgeType
                                    : formatRelationType}/>
                            }
                            <div className="graph-footnote">当前视图 {visibleNodes.length} 个节点
                                / {visibleEdges.length} 条关系 · 点击节点查看证据
                            </div>
                        </div>
                    </>}

                    {view === 'documents' && <DocumentPanel
                        documents={persistedDocuments}
                        search={documentSearch}
                        onSearchChange={(value) => {
                            const normalizedSearch = value.trim();
                            documentLoadMorePendingRef.current = false;
                            setDocumentLoadMoreError(null);
                            if (normalizedSearch !== documentSearchQuery) {
                                suppressDocumentSearchNoticeRef.current = true;
                                documentSearchPendingRef.current = true;
                            } else {
                                suppressDocumentSearchNoticeRef.current = false;
                                documentSearchPendingRef.current = false;
                            }
                            setDocumentSearch(value);
                            setDocumentPage(1);
                        }}
                        onPreview={(document) => setDocumentPreview({document, initialTab: 'rendered'})}
                        onDelete={(document) => setDeleteConfirmation({kind: 'document', item: document})}
                        onExtract={(document) => void extractDocument(document)}
                        selectedDocumentIds={selectedDocumentIds}
                        onToggleSelection={(documentId) => setSelectedDocumentIds((current) => ({
                            ...current,
                            [documentId]: !current[documentId],
                        }))}
                        onBatchDelete={(documents) => setDeleteConfirmation({kind: 'documents', items: documents})}
                        onBatchExtract={(documents) => void extractDocuments(documents)}
                        isBatchDeleting={isBatchDeleting}
                        isBatchExtracting={isBatchExtracting}
                        deletingDocumentId={deletingDocumentId}
                        extractionStates={documentExtractionStates}
                        total={documentTotal}
                        isLoadingDocuments={isLoadingDocuments}
                        isLoadingMore={isLoadingMoreDocuments}
                        loadMoreError={documentLoadMoreError}
                        onLoadMore={loadMoreDocuments}
                        onRetryLoadMore={retryLoadMoreDocuments}
                    />}
                    {view === 'health' && <HealthPanel graph={graph} onSelectNode={(id) => {
                        setSelectedNodeId(id);
                        setView('graph');
                    }}/>}
                    </>}
                </section>

                <aside className="detail-panel">
                    {!currentSpaceId
                        ? <div className="empty-detail"><Archive size={28}/><p>创建知识空间后即可开始导入文档</p></div>
                        : view === 'documents'
                        ? <DocumentSidebar documents={persistedDocuments} space={currentSpace}/>
                        : graphMode === 'document'
                            ? <DocumentGraphSidebar
                                graph={filteredDocumentGraph}
                                selectedNodeId={selectedNodeId}
                                onOpenDocument={(documentId) => openDocumentGraphDocument(documentId)}
                                onOpenEvidence={(documentId, evidence) => openDocumentGraphDocument(documentId, evidence)}/>
                        : selectedNode
                            ? <NodeDetail node={selectedNode} edges={selectedEdges} graph={graph}
                                onSelectNode={setSelectedNodeId}/>
                            : <div className="empty-detail"><Link2 size={28}/><p>选择一个节点查看它的上下文</p></div>}
                </aside>
            </div>
            {documentPreview && currentSpaceId && <DocumentPreviewModal
                document={documentPreview.document}
                spaceId={currentSpaceId}
                initialTab={documentPreview.initialTab}
                evidence={documentPreview.evidence}
                extractionState={documentExtractionStates[documentPreview.document.id]}
                extractionView={extractionView?.documentId === documentPreview.document.id ? extractionView : null}
                extractionLoadError={extractionResultLoadErrors[documentPreview.document.id]}
                isLoadingExtraction={loadingExtractionResultId === documentPreview.document.id}
                reviewStatuses={aiRelationReviewStatuses}
                reviewSelections={aiRelationReviewSelections}
                onLoadExtraction={() => void viewExtractionResult(documentPreview.document)}
                onExtract={() => void extractDocument(documentPreview.document)}
                onReviewRelations={reviewAiRelations}
                onSelectRelation={updateAiRelationReviewSelection}
                onTagsChanged={() => setTagRefreshKey((current) => current + 1)}
                onClose={closeDocumentPreview}
            />}
            {isSpaceFormOpen && <KnowledgeSpaceFormModal
                name={newSpaceName}
                description={newSpaceDescription}
                isSubmitting={isManagingSpace}
                onNameChange={setNewSpaceName}
                onDescriptionChange={setNewSpaceDescription}
                onSubmit={() => void submitNewSpace()}
                onClose={() => setIsSpaceFormOpen(false)}
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
                    if (target.kind === 'documents') {
                        void removeDocuments(target.items);
                        return;
                    }
                    void removeDocument(target.item);
                }}
            />}
        </main>
    );
}

function KnowledgeSpaceFormModal({
                                    name,
                                    description,
                                    isSubmitting,
                                    onNameChange,
                                    onDescriptionChange,
                                    onSubmit,
                                    onClose,
                                }: {
    name: string;
    description: string;
    isSubmitting: boolean;
    onNameChange: (value: string) => void;
    onDescriptionChange: (value: string) => void;
    onSubmit: () => void;
    onClose: () => void;
}) {
    const close = () => {
        if (!isSubmitting) {
            // 关闭创建弹窗，保留父组件对表单状态的统一管理
            onClose();
        }
    };

    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') close();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [isSubmitting, onClose]);

    return <div className="document-preview-backdrop space-form-backdrop" role="presentation" onClick={close}>
        <section className="space-form-dialog" role="dialog" aria-modal="true"
            aria-labelledby="space-form-title" onClick={(event) => event.stopPropagation()}>
            <header className="space-form-header">
                <div>
                    <div className="eyebrow">知识空间 / 新建</div>
                    <h2 id="space-form-title">创建知识空间</h2>
                </div>
                <button className="space-icon-button" type="button" aria-label="关闭创建知识空间" title="关闭"
                    disabled={isSubmitting} onClick={close}><X size={16}/></button>
            </header>
            <form className="space-form" onSubmit={(event) => {
                event.preventDefault();
                // 提交当前弹窗表单，复用父组件已有的创建和校验逻辑
                onSubmit();
            }}>
                <div className="space-form-content">
                    <p>创建后即可导入文档、执行 AI 抽取和生成图谱。</p>
                    <input autoFocus aria-label="知识空间名称" value={name}
                        onChange={(event) => onNameChange(event.target.value)} placeholder="知识空间名称"
                        maxLength={40}/>
                    <input aria-label="知识空间说明" value={description}
                        onChange={(event) => onDescriptionChange(event.target.value)}
                        placeholder="用途说明（可选）" maxLength={200}/>
                </div>
                <div className="space-form-actions">
                    <button className="ghost-button" type="button" disabled={isSubmitting} onClick={close}>取消</button>
                    <button className="primary-button" type="submit" disabled={isSubmitting}>
                        {isSubmitting ? '创建中' : '创建'}
                    </button>
                </div>
            </form>
        </section>
    </div>;
}

function formatFileSize(fileSize?: number) {
    if (fileSize == null) return '未知大小';
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
                           search,
                           onSearchChange,
                           onPreview,
                           onDelete,
                           onExtract,
                           selectedDocumentIds,
                           onToggleSelection,
                           onBatchDelete,
                           onBatchExtract,
                           isBatchDeleting,
                           isBatchExtracting,
                           deletingDocumentId,
                           extractionStates,
                           total,
                           isLoadingDocuments,
                           isLoadingMore,
                           loadMoreError,
                           onLoadMore,
                           onRetryLoadMore,
                       }: {
    documents: SourceDocument[];
    search: string;
    onSearchChange: (value: string) => void;
    onPreview: (document: SourceDocument) => void;
    onDelete: (document: SourceDocument) => void;
    onExtract: (document: SourceDocument) => void;
    selectedDocumentIds: Record<string, boolean>;
    onToggleSelection: (documentId: string) => void;
    onBatchDelete: (documents: SourceDocument[]) => void;
    onBatchExtract: (documents: SourceDocument[]) => void;
    isBatchDeleting: boolean;
    isBatchExtracting: boolean;
    deletingDocumentId: string | null;
    extractionStates: Record<string, DocumentExtractionState>;
    total: number;
    isLoadingDocuments: boolean;
    isLoadingMore: boolean;
    loadMoreError: string | null;
    onLoadMore: () => void;
    onRetryLoadMore: () => void;
}) {
    const loadMoreRef = useRef<HTMLDivElement>(null);
    const selectedDocuments = documents.filter((document) => selectedDocumentIds[document.id]);
    const hasProcessingSelection = selectedDocuments.some(
        (document) => extractionStates[document.id]?.status === 'processing'
    );
    const batchActionsDisabled = isBatchDeleting || isBatchExtracting || hasProcessingSelection;
    const batchActionTitle = hasProcessingSelection
        ? '所选资料包含提取中的任务，请等待完成后再执行批量操作'
        : undefined;

    useEffect(() => {
        const target = loadMoreRef.current;
        if (!target || isLoadingDocuments || isLoadingMore || loadMoreError || !total || documents.length >= total) {
            return;
        }
        const observer = new IntersectionObserver((entries) => {
            if (entries.some((entry) => entry.isIntersecting)) onLoadMore();
        }, {rootMargin: '320px 0px'});
        observer.observe(target);
        return () => observer.disconnect();
    }, [documents.length, isLoadingDocuments, isLoadingMore, loadMoreError, onLoadMore, total]);

    if (!documents.length) {
        return <>
            <DocumentSearchBox value={search} onChange={onSearchChange}/>
            <div className="state-card document-empty">
                {isLoadingDocuments ? <LoaderCircle className="spin" size={28}/> : <FileText size={28}/>}
                <h3>{isLoadingDocuments ? '正在加载来源资料' : search.trim() ? '未找到匹配资料' : '尚未导入来源资料'}</h3>
                <p>{isLoadingDocuments
                    ? '正在从当前知识空间读取资料，请稍候。'
                    : search.trim() ? '请尝试其他文件名，或清空搜索条件恢复完整列表。' : '点击右上角“导入资料”，选择 UTF-8 Markdown、TXT 或可复制文本 PDF 文件。'}</p>
            </div>
        </>;
    }

    return <>
        <DocumentSearchBox value={search} onChange={onSearchChange}/>
        <div className="document-batch-toolbar">
            <span role="status" aria-live="polite" aria-atomic="true">{selectedDocuments.length
                ? `已选择 ${selectedDocuments.length} 份资料`
                : '点击资料卡片即可多选，再执行批量操作'}</span>
            <div className="document-batch-actions">
                <button
                    className="secondary-button"
                    type="button"
                    title={batchActionTitle}
                    disabled={!selectedDocuments.length || batchActionsDisabled}
                    onClick={() => onBatchExtract(selectedDocuments)}
                >{isBatchExtracting ? <LoaderCircle className="spin" size={14}/> : <Sparkles size={14}/>} 批量提取</button>
                <button
                    className="secondary-button danger-button"
                    type="button"
                    title={batchActionTitle}
                    disabled={!selectedDocuments.length || batchActionsDisabled}
                    onClick={() => onBatchDelete(selectedDocuments)}
                >{isBatchDeleting ? <LoaderCircle className="spin" size={14}/> : <Trash2 size={14}/>} 批量删除</button>
            </div>
        </div>
        <div className="document-grid">
            {documents.map((document) => {
                const extractionState = extractionStates[document.id];
                const isExtracting = extractionState?.status === 'processing';
                const extractionButtonLabel = isExtracting
                    ? '提取中…'
                    : extractionState?.status === 'error'
                        ? '重试提取'
                        : extractionState?.status === 'success'
                            ? '重新提取'
                            : 'AI 提取';
                const deleteButtonLabel = deletingDocumentId === document.id
                    ? `正在删除来源资料：${document.name}`
                    : `删除来源资料：${document.name}`;
                const isSelected = Boolean(selectedDocumentIds[document.id]);
                return <article
                    className={`document-card ${isSelected ? 'selected' : ''}`}
                    key={document.id}
                    data-selected={isSelected}
                    aria-label={`来源资料：${document.name}，${isSelected ? '已选择' : '未选择'}，按回车或空格切换选择`}
                    tabIndex={0}
                    onClick={(event) => {
                        if (event.target instanceof Element && event.target.closest('button')) return;
                        onToggleSelection(document.id);
                    }}
                    onKeyDown={(event) => {
                        if (event.target instanceof Element && event.target.closest('button')) return;
                        if (event.key !== 'Enter' && event.key !== ' ') return;
                        event.preventDefault();
                        onToggleSelection(document.id);
                    }}
                >
                    <div className="document-card-format">
                        <div className="document-card-head"><span className="document-kind"><FileText
                            size={16}/>{documentKindLabels[document.kind]}</span>
                            <div className="document-status-group"><span
                                className="document-status">已解析</span>{extractionState &&
                                <span className={`ai-document-status ${extractionState.status}`}
                                    title={extractionState.message}>{isExtracting && <LoaderCircle className="spin"
                                size={11}/>}{extractionState.status === 'processing' ? 'AI 提取中' : extractionState.status === 'success' ? 'AI 提取完成' : 'AI 提取失败'}</span>}
                            </div>
                        </div>
                        <button
                            className="space-icon-button danger document-delete-button"
                            type="button"
                            aria-label={deleteButtonLabel}
                            title={deleteButtonLabel}
                            disabled={deletingDocumentId === document.id || isExtracting || isBatchDeleting || isBatchExtracting}
                            onClick={() => onDelete(document)}
                        >{deletingDocumentId === document.id ? <LoaderCircle className="spin" size={14}/> : <Trash2 size={14}/>}</button>
                    </div>
                    <div className="document-card-name">
                        <h3 title={document.name}>{document.name}</h3>
                    </div>
                    <div className="document-card-content">
                        <p>{document.excerpt}</p>
                    </div>
                    <div className="document-card-meta">
                        <div className="document-meta">
                            <span>{formatFileSize(document.fileSize)}</span><span>{formatImportedAt(document.importedAt)}</span>
                        </div>
                    </div>
                    <div className="document-card-actions">
                        <button className="secondary-button" disabled={isBatchDeleting} onClick={() => onPreview(document)}><Eye size={14}/> 查看
                        </button>
                        <button className="secondary-button" disabled={isExtracting || isBatchDeleting || isBatchExtracting}
                            onClick={() => onExtract(document)}>{isExtracting ?
                            <LoaderCircle className="spin" size={14}/> :
                            <Sparkles size={14}/>} {extractionButtonLabel}</button>
                    </div>
                </article>;
            })}
        </div>
        <div ref={loadMoreRef} className="document-load-more" role="status" aria-live="polite">
            {loadMoreError ? <>
                <span>加载更多失败：{loadMoreError}</span>
                <button className="secondary-button" type="button" onClick={onRetryLoadMore}>重试</button>
            </> : isLoadingMore ? <>
                <LoaderCircle className="spin" size={15}/>
                <span>正在加载更多来源资料…</span>
            </> : documents.length < total ? <span>继续下滑加载更多来源资料</span> : <span>已加载全部 {total} 份来源资料</span>}
        </div>
    </>;
}

function DocumentSearchBox({value, onChange}: { value: string; onChange: (value: string) => void }) {
    return <div className="document-search-box">
        <Search size={16}/>
        <input
            value={value}
            onChange={(event) => onChange(event.target.value)}
            placeholder="按文件名搜索来源资料"
            aria-label="按文件名搜索来源资料"
        />
        {value && <button
            type="button"
            className="space-icon-button"
            onClick={() => onChange('')}
            aria-label="清空来源资料搜索"
            title="清空搜索"
        ><X size={14}/></button>}
    </div>;
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
    const isDocumentBatch = target.kind === 'documents';
    const documentCount = isDocumentBatch ? target.items.length : 1;
    const targetName = isDocumentBatch
        ? `已选择 ${documentCount} 份来源资料`
        : target.item.name;

    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') onCancel();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [onCancel]);

    return <div className="document-preview-backdrop confirmation-backdrop" role="presentation" onClick={onCancel}>
        <section className="confirmation-dialog" role="dialog" aria-modal="true"
            aria-labelledby="delete-confirmation-title" aria-describedby="delete-confirmation-description"
            onClick={(event) => event.stopPropagation()}>
            <header className="confirmation-header">
                <span className="confirmation-icon"><AlertTriangle size={21}/></span>
                <div>
                    <div className="eyebrow">{isSpace ? '知识空间 / 移除确认' : `来源资料 / ${isDocumentBatch ? '批量删除确认' : '删除确认'}`}</div>
                    <h2 id="delete-confirmation-title">{isSpace ? '确认移除知识空间？' : isDocumentBatch ? `确认删除 ${documentCount} 份来源资料？` : '确认删除来源资料？'}</h2>
                </div>
                <button className="space-icon-button" aria-label="关闭删除确认" title="关闭" onClick={onCancel}><X
                    size={16}/></button>
            </header>
            <div className="confirmation-content">
                <div className="confirmation-target">
                    {isSpace ? <Archive size={18}/> : <FileText size={18}/>}
                    <div><span>{isSpace ? '待移除知识空间' : isDocumentBatch ? '待批量删除来源资料' : '待删除来源资料'}</span><strong
                        title={targetName}>{targetName}</strong></div>
                </div>
                <p id="delete-confirmation-description">{isSpace
                    ? '移除后，该空间将不再出现在工作台中；来源资料和图谱事实仍会保留在本地数据库中。'
                    : `删除后，仅由${isDocumentBatch ? '这些资料' : '该资料'}支撑的图谱节点和关系会同步失效；原始文件与历史证据仍会保留。`}</p>
            </div>
            <footer className="confirmation-actions">
                <button className="ghost-button" autoFocus onClick={onCancel}>取消</button>
                <button className="secondary-button danger-button confirmation-submit" onClick={onConfirm}><Trash2
                    size={15}/>{isSpace ? '确认移除' : isDocumentBatch ? '确认批量删除' : '确认删除'}</button>
            </footer>
        </section>
    </div>;
}

function DocumentPreviewModal({
                                  document,
                                  spaceId,
                                  initialTab,
                                  evidence,
                                  extractionState,
                                  extractionView,
                                  extractionLoadError,
                                  isLoadingExtraction,
                                  reviewStatuses,
                                  reviewSelections,
                                  onLoadExtraction,
                                  onExtract,
                                  onReviewRelations,
                                  onSelectRelation,
                                  onTagsChanged,
                                  onClose,
                              }: {
    document: SourceDocument;
    spaceId: string;
    initialTab: DocumentPreviewTab;
    evidence?: DocumentGraphEvidence;
    extractionState?: DocumentExtractionState;
    extractionView: AiExtractionViewState | null;
    extractionLoadError?: string;
    isLoadingExtraction: boolean;
    reviewStatuses: Record<string, AiRelationReviewStatus>;
    reviewSelections: AiRelationReviewSelection;
    onLoadExtraction: () => void;
    onExtract: () => void;
    onReviewRelations: (
        extractionId: string,
        documentId: string,
        decisions: AiRelationReviewDecision[]
    ) => Promise<void>;
    onSelectRelation: (selectionKey: string, selected: boolean) => void;
    onTagsChanged: () => void;
    onClose: () => void;
}) {
    const [content, setContent] = useState<SourceDocumentContent | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [previewMode, setPreviewMode] = useState<DocumentPreviewTab>(initialTab);
    const renderedModeLabel = document.kind === 'pdf' ? '文本预览' : '渲染预览';
    const previewModeLabel = previewMode === 'rendered'
        ? renderedModeLabel
        : previewMode === 'source'
            ? '原文预览'
            : 'AI 输出';
    const completedExtractionId = extractionState?.status === 'success'
        ? extractionState.extractionId
        : document.latestCompletedExtractionId;

    useEffect(() => {
        // 卡片操作切换同一资料的目标 Tab 时，同步弹窗当前视图
        setPreviewMode(initialTab);
    }, [initialTab]);

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
        return () => {
            cancelled = true;
        };
    }, [document.id, spaceId]);

    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [onClose]);

    useEffect(() => {
        if (
            previewMode !== 'ai'
            || extractionView?.result
            || extractionView?.status === 'processing'
            || extractionView?.status === 'connecting'
            || !completedExtractionId
            || isLoadingExtraction
            || extractionLoadError
        ) {
            return;
        }

        // AI Tab 不依赖前端内存；存在成功运行时按 extractionId 恢复服务端完整结果
        onLoadExtraction();
    }, [
        completedExtractionId,
        extractionLoadError,
        extractionView,
        isLoadingExtraction,
        onLoadExtraction,
        previewMode,
    ]);

    return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
        <section className={`document-preview-dialog ${previewMode === 'ai' ? 'ai-extraction-dialog' : ''}`} role="dialog" aria-modal="true"
            aria-labelledby="document-preview-title" onClick={(event) => event.stopPropagation()}>
            <header className="document-preview-header">
                <div>
                    <div className="eyebrow">来源资料 / {previewModeLabel}</div>
                    <h2 id="document-preview-title" title={document.name}>{document.name}</h2>
                </div>
                <button className="space-icon-button" aria-label={`关闭${previewModeLabel}`} title="关闭"
                    onClick={onClose}><X size={16}/></button>
            </header>
            {evidence && <div className="document-evidence-location">
                <MapPin size={15}/><div><strong>已定位关系证据 · {evidence.sectionPath || '原文'}</strong>
                    <span>“{evidence.quote}” · 分片 {evidence.chunkId}</span></div>
            </div>}
            <div className="document-preview-meta">
                <div className="document-preview-mode" role="tablist" aria-label="来源资料查看模式">
                    <button
                        className={previewMode === 'rendered' ? 'preview-mode-button active' : 'preview-mode-button'}
                        type="button"
                        role="tab"
                        aria-selected={previewMode === 'rendered'}
                        onClick={() => setPreviewMode('rendered')}
                    >
                        <Eye size={13}/> {renderedModeLabel}
                    </button>
                    <button
                        className={previewMode === 'source' ? 'preview-mode-button active' : 'preview-mode-button'}
                        type="button"
                        role="tab"
                        aria-selected={previewMode === 'source'}
                        onClick={() => setPreviewMode('source')}
                    >
                        <FileText size={13}/> 原文预览
                    </button>
                    <button
                        className={previewMode === 'ai' ? 'preview-mode-button active' : 'preview-mode-button'}
                        type="button"
                        role="tab"
                        aria-selected={previewMode === 'ai'}
                        onClick={() => setPreviewMode('ai')}
                    >
                        <Sparkles size={13}/> AI 输出
                    </button>
                </div>
                {previewMode === 'ai'
                    ? <span>AI 候选必须经过证据校验和人工审核</span>
                    : content?.kind === 'pdf' && <span>服务端按页提取文本 · 不包含 OCR</span>}
                {previewMode === 'rendered' && <span
                    className="document-preview-hash"
                    title={`完整 SHA-256 内容指纹：${content?.contentHash ?? document.contentHash}`}
                >SHA-256 · {(content?.contentHash ?? document.contentHash).slice(0, 16)}…</span>}
            </div>
            {previewMode === 'ai'
                ? <AiOverviewTab
                    document={document}
                    spaceId={spaceId}
                    extractionState={extractionState}
                    view={extractionView}
                    loadError={extractionLoadError}
                    isLoading={isLoadingExtraction}
                    hasCompletedResult={Boolean(completedExtractionId)}
                    reviewStatuses={reviewStatuses}
                    reviewSelections={reviewSelections}
                    onLoad={onLoadExtraction}
                    onExtract={onExtract}
                    onReviewRelations={(decisions) => onReviewRelations(
                        extractionView?.result?.extractionId ?? extractionView?.extractionId ?? '',
                        document.id,
                        decisions
                    )}
                    onSelectRelation={onSelectRelation}
                    onTagsChanged={onTagsChanged}
                    onClose={onClose}
                />
                : isLoading
                    ? <div className="document-preview-state"><LoaderCircle className="spin"
                        size={22}/><span>正在加载原文…</span></div>
                    : error
                        ? <div className="document-preview-state error"><AlertTriangle
                            size={22}/><span>原文加载失败：{error}</span>
                            <button className="secondary-button" onClick={onClose}>关闭</button>
                        </div>
                        : content && (previewMode === 'rendered'
                            ? <article className="document-preview-markdown"><ReactMarkdown
                                remarkPlugins={[remarkGfm]}>{content.contentText}</ReactMarkdown></article>
                            : <pre className="document-preview-content">{evidence?.quote && content.contentText.includes(evidence.quote)
                                ? content.contentText.split(evidence.quote).map((part, index, parts) => <span key={`${part}-${index}`}>
                                    {part}{index < parts.length - 1 && <mark className="document-evidence-highlight">{evidence.quote}</mark>}
                                </span>)
                                : content.contentText}</pre>)}
        </section>
    </div>;
}

function AiOverviewTab({
                           document,
                           spaceId,
                           extractionState,
                           view,
                           loadError,
                           isLoading,
                           hasCompletedResult,
                           reviewStatuses,
                           reviewSelections,
                           onLoad,
                           onExtract,
                           onReviewRelations,
                           onSelectRelation,
                           onTagsChanged,
                           onClose,
                       }: {
    document: SourceDocument;
    spaceId: string;
    extractionState?: DocumentExtractionState;
    view: AiExtractionViewState | null;
    loadError?: string;
    isLoading: boolean;
    hasCompletedResult: boolean;
    reviewStatuses: Record<string, AiRelationReviewStatus>;
    reviewSelections: AiRelationReviewSelection;
    onLoad: () => void;
    onExtract: () => void;
    onReviewRelations: (decisions: AiRelationReviewDecision[]) => Promise<void>;
    onSelectRelation: (selectionKey: string, selected: boolean) => void;
    onTagsChanged: () => void;
    onClose: () => void;
}) {
    const [section, setSection] = useState<'tags' | 'entities'>('tags');

    useEffect(() => {
        if (view?.status === 'connecting' || view?.status === 'processing') {
            setSection('entities');
        }
    }, [view?.status]);

    return <div className="ai-overview-panel">
        <div className="ai-overview-navigation" role="tablist" aria-label="AI 输出分类">
            <button
                className={section === 'tags' ? 'active' : ''}
                type="button"
                role="tab"
                aria-selected={section === 'tags'}
                onClick={() => setSection('tags')}
            ><Tags size={14}/> 文档标签</button>
            <button
                className={section === 'entities' ? 'active' : ''}
                type="button"
                role="tab"
                aria-selected={section === 'entities'}
                onClick={() => setSection('entities')}
            ><GitBranch size={14}/> 实体与关系（兼容）</button>
        </div>
        <div className="ai-overview-content">
            {section === 'tags'
                ? <DocumentTagPanel
                    document={document}
                    spaceId={spaceId}
                    onTagsChanged={onTagsChanged}
                />
                : <AiExtractionTab
                    extractionState={extractionState}
                    view={view}
                    loadError={loadError}
                    isLoading={isLoading}
                    hasCompletedResult={hasCompletedResult}
                    reviewStatuses={reviewStatuses}
                    reviewSelections={reviewSelections}
                    onLoad={onLoad}
                    onExtract={onExtract}
                    onReviewRelations={onReviewRelations}
                    onSelectRelation={onSelectRelation}
                    onClose={onClose}
                />}
        </div>
    </div>;
}

function DocumentTagPanel({
                              document,
                              spaceId,
                              onTagsChanged,
                          }: {
    document: SourceDocument;
    spaceId: string;
    onTagsChanged: () => void;
}) {
    const [tags, setTags] = useState<DocumentTag[]>([]);
    const [latestRun, setLatestRun] = useState<DocumentTaggingRun | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isTagging, setIsTagging] = useState(false);
    const [isReviewing, setIsReviewing] = useState(false);
    const [isAssociating, setIsAssociating] = useState(false);
    const [associationRun, setAssociationRun] = useState<DocumentAssociationRun | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [selectedTagIds, setSelectedTagIds] = useState<Record<string, boolean>>({});

    const loadTagOverview = useCallback(async (showLoading: boolean) => {
        if (showLoading) setIsLoading(true);
        setLoadError(null);
        try {
            const [loadedTags, loadedRun] = await Promise.all([
                listDocumentTags(spaceId, document.id),
                getLatestDocumentTaggingRun(spaceId, document.id),
            ]);
            setTags(loadedTags);
            setLatestRun(loadedRun);
            setSelectedTagIds((current) => loadedTags
                .filter((tag) => tag.status === 'suggested' && current[tag.id])
                .reduce((next, tag) => ({...next, [tag.id]: true}), {}));
        } catch (error) {
            setLoadError(error instanceof Error ? error.message : '文档标签加载失败');
        } finally {
            if (showLoading) setIsLoading(false);
        }
    }, [document.id, spaceId]);

    useEffect(() => {
        void loadTagOverview(true);
    }, [loadTagOverview]);

    useEffect(() => {
        if (latestRun?.status !== 'processing' || isTagging) return;
        const pollingTimer = window.setInterval(() => {
            void loadTagOverview(false);
        }, 2000);
        return () => window.clearInterval(pollingTimer);
    }, [isTagging, latestRun?.status, loadTagOverview]);

    const startTagging = async () => {
        setIsTagging(true);
        setActionError(null);
        try {
            const run = await createDocumentTaggingRun(spaceId, document.id);
            setLatestRun(run);
            const loadedTags = await listDocumentTags(spaceId, document.id);
            setTags(loadedTags);
            setSelectedTagIds({});
            onTagsChanged();
        } catch (error) {
            setActionError(`标签生成请求失败：${error instanceof Error ? error.message : '未知错误'}`);
            await loadTagOverview(false);
        } finally {
            setIsTagging(false);
        }
    };

    const submitReviews = async (
        documentTagIds: string[],
        action: DocumentTagReviewAction
    ) => {
        if (!documentTagIds.length || isReviewing) return;
        setIsReviewing(true);
        setActionError(null);
        try {
            await reviewDocumentTags(
                spaceId,
                document.id,
                documentTagIds.map((documentTagId) => ({documentTagId, action}))
            );
            const loadedTags = await listDocumentTags(spaceId, document.id);
            setTags(loadedTags);
            setSelectedTagIds({});
            onTagsChanged();
        } catch (error) {
            setActionError(`标签审核未保存：${error instanceof Error ? error.message : '未知错误'}`);
            try {
                const restoredTags = await listDocumentTags(spaceId, document.id);
                setTags(restoredTags);
                setSelectedTagIds({});
                // 同步刷新左侧 confirmed 标签统计，避免并发审核后的面板和导航状态不一致
                onTagsChanged();
            } catch (refreshError) {
                setLoadError(`标签状态刷新失败：${refreshError instanceof Error ? refreshError.message : '未知错误'}`);
            }
        } finally {
            setIsReviewing(false);
        }
    };

    const startConfirmedTagAssociation = async () => {
        if (isAssociating) return;
        setIsAssociating(true);
        setActionError(null);
        try {
            // 仅在用户明确点击后开启 confirmed 标签补充候选通道
            const run = await createDocumentAssociationRun(spaceId, document.id, true);
            setAssociationRun(run);
        } catch (error) {
            setActionError(`标签补充候选失败：${error instanceof Error ? error.message : '未知错误'}`);
        } finally {
            setIsAssociating(false);
        }
    };

    const pendingTags = tags.filter((tag) => tag.status === 'suggested');
    const selectedPendingTagIds = pendingTags
        .map((tag) => tag.id)
        .filter((tagId) => selectedTagIds[tagId]);
    const allPendingTagsSelected = pendingTags.length > 0
        && selectedPendingTagIds.length === pendingTags.length;
    const confirmedCount = tags.filter((tag) => tag.status === 'confirmed').length;
    const rejectedCount = tags.filter((tag) => tag.status === 'rejected').length;
    const staleCount = tags.filter((tag) => tag.status === 'stale').length;
    const runIsProcessing = isTagging || latestRun?.status === 'processing';

    const toggleAllPendingTags = () => {
        setSelectedTagIds((current) => pendingTags.reduce((next, tag) => ({
            ...next,
            [tag.id]: !allPendingTagsSelected,
        }), {...current}));
    };

    return <div className="document-tag-panel">
        <header className="document-tag-header">
            <div><span>可选增强</span><h3>文档标签</h3>
                <p>标签必须引用当前原文；只有已确认标签进入左侧导航。</p></div>
            <div className="document-tag-header-actions">
                <button className="secondary-button" type="button" disabled={runIsProcessing || isAssociating}
                    onClick={() => void startConfirmedTagAssociation()}>
                    {isAssociating ? <LoaderCircle className="spin" size={14}/> : <Link2 size={14}/>}
                    {isAssociating ? '正在补充候选' : '按已确认标签补充候选'}
                </button>
                <button className="primary-button" type="button" disabled={runIsProcessing || isAssociating}
                    onClick={() => void startTagging()}>
                    {runIsProcessing ? <LoaderCircle className="spin" size={14}/> : <Sparkles size={14}/>}
                    {runIsProcessing ? '正在生成标签' : latestRun ? '重新生成标签' : '生成候选标签'}
                </button>
            </div>
        </header>

        {loadError && <div className="document-tag-message error"><AlertTriangle size={15}/>
            <span>标签概览加载失败：{loadError}</span>
            <button type="button" onClick={() => void loadTagOverview(true)}>重试</button>
        </div>}
        {actionError && <div className="document-tag-message error"><AlertTriangle size={15}/>
            <span>{actionError}</span>
        </div>}
        {runIsProcessing && <div className="document-tag-run processing"><LoaderCircle className="spin" size={16}/>
            <div><strong>标签 Pipeline 正在处理</strong>
                <span>服务端正在解析、分片、生成候选并校验证据；刷新后会从最近运行恢复。</span></div>
        </div>}
        {!runIsProcessing && latestRun?.status === 'failed' && <div className="document-tag-run failed"><AlertTriangle size={16}/>
            <div><strong>最近一次标签运行失败</strong>
                <span>{latestRun.errorMessage || '服务端没有保存不完整的标签候选。'}{latestRun.failureStage
                    ? ` · 阶段 ${latestRun.failureStage}`
                    : ''}</span></div>
        </div>}
        {!runIsProcessing && latestRun?.status === 'completed' && <div className="document-tag-run completed"><Check size={16}/>
            <div><strong>最近一次标签运行已完成</strong>
                <span>{latestRun.summary || '模型未返回可展示摘要。'} · 新增 {latestRun.suggestionCount} 个候选</span></div>
        </div>}
        {associationRun?.status === 'completed' && <div className="document-tag-run completed"><Link2 size={16}/>
            <div><strong>标签补充候选已完成</strong>
                <span>比较 {associationRun.comparedCount} 份候选，新增 {associationRun.suggestionCount} 条关系建议；标签通道命中 {associationRun.tagCandidateCount} 份。</span></div>
        </div>}
        {associationRun?.status === 'failed' && <div className="document-tag-run failed"><AlertTriangle size={16}/>
            <div><strong>标签补充候选未完成</strong>
                <span>{associationRun.errorMessage || '服务端未启用文档关联判断。'}{associationRun.failureStage ? ` · 阶段 ${associationRun.failureStage}` : ''}</span></div>
        </div>}

        <div className="document-tag-summary">
            <div><strong>{pendingTags.length}</strong><span>待审核</span></div>
            <div><strong>{confirmedCount}</strong><span>已确认</span></div>
            <div><strong>{rejectedCount}</strong><span>已拒绝</span></div>
            <div><strong>{staleCount}</strong><span>需重评</span></div>
        </div>

        {pendingTags.length > 0 && <div className="document-tag-bulk-toolbar">
            <button className={allPendingTagsSelected ? 'selected' : ''} type="button"
                aria-pressed={allPendingTagsSelected} onClick={toggleAllPendingTags}>
                <Check size={14}/> {allPendingTagsSelected ? '取消全选' : '全选待审核标签'}
            </button>
            <span>已选择 {selectedPendingTagIds.length} 个</span>
            <div>
                <button className="secondary-button" type="button" disabled={!selectedPendingTagIds.length || isReviewing}
                    onClick={() => void submitReviews(selectedPendingTagIds, 'reject')}><X size={14}/> 批量拒绝</button>
                <button className="primary-button" type="button" disabled={!selectedPendingTagIds.length || isReviewing}
                    onClick={() => void submitReviews(selectedPendingTagIds, 'accept')}><Check size={14}/> 批量采纳</button>
            </div>
        </div>}

        <div className="document-tag-content">
            {isLoading
                ? <div className="document-tag-empty"><LoaderCircle className="spin" size={22}/><span>正在恢复标签和审核状态…</span></div>
                : tags.length
                    ? <div className="document-tag-list">{tags.map((tag) => {
                        const selectable = tag.status === 'suggested';
                        const selected = Boolean(selectedTagIds[tag.id]);
                        const latestReview = tag.reviews[tag.reviews.length - 1];
                        return <article
                            className={`document-tag-card ${tag.status} ${selected ? 'selected' : ''}`}
                            key={tag.id}
                            tabIndex={selectable ? 0 : undefined}
                            aria-label={selectable ? `${selected ? '取消选择' : '选择'}标签：${tag.name}` : undefined}
                            onClick={(event) => {
                                if (!selectable || (event.target as HTMLElement).closest('button')) return;
                                setSelectedTagIds((current) => ({...current, [tag.id]: !selected}));
                            }}
                            onKeyDown={(event) => {
                                if (!selectable
                                    || (event.target as HTMLElement).closest('button')
                                    || (event.key !== 'Enter' && event.key !== ' ')) return;
                                event.preventDefault();
                                setSelectedTagIds((current) => ({...current, [tag.id]: !selected}));
                            }}
                        >
                            <div className="document-tag-card-heading">
                                <div><Tags size={15}/><strong>{tag.name}</strong></div>
                                <div>{selected && <span className="document-tag-selected"><Check size={12}/></span>}
                                    {tag.confidence != null && <em>置信度 {Math.round(tag.confidence * 100)}%</em>}
                                    <span className={`document-tag-status ${tag.status}`}>{formatDocumentTagStatus(tag.status)}</span>
                                </div>
                            </div>
                            {tag.evidences.length
                                ? <div className="document-tag-evidence">{tag.evidences.map((evidence) => <blockquote
                                    key={evidence.id ?? `${evidence.chunkId}:${evidence.startOffset ?? evidence.quote}`}>
                                    “{evidence.quote}”<span>{evidence.sectionPath}</span>
                                </blockquote>)}</div>
                                : <p className="ai-review-no-evidence">没有可显示的原文证据</p>}
                            <div className="document-tag-card-footer">
                                {selectable
                                    ? <div className="document-tag-actions">
                                        <button className="secondary-button" type="button" disabled={isReviewing}
                                            onClick={() => void submitReviews([tag.id], 'reject')}><X size={13}/> 拒绝</button>
                                        <button className="primary-button" type="button" disabled={isReviewing}
                                            onClick={() => void submitReviews([tag.id], 'accept')}><Check size={13}/> 采纳</button>
                                    </div>
                                    : <span>{latestReview
                                        ? `${latestReview.operatorName} · ${latestReview.action === 'accept' ? '已采纳' : '已拒绝'}`
                                        : tag.sourceType === 'user' ? '用户手工确认' : '当前没有审核历史'}</span>}
                            </div>
                        </article>;
                    })}</div>
                    : <div className="document-tag-empty"><Tags size={24}/><strong>尚未生成标签</strong>
                        <span>标签为空不会阻塞默认文档内容关联；需要时再生成并审核。</span></div>}
        </div>
    </div>;
}

function AiExtractionTab({
                             extractionState,
                             view,
                             loadError,
                             isLoading,
                             hasCompletedResult,
                             reviewStatuses,
                             reviewSelections,
                             onLoad,
                             onExtract,
                             onReviewRelations,
                             onSelectRelation,
                             onClose,
                         }: {
    extractionState?: DocumentExtractionState;
    view: AiExtractionViewState | null;
    loadError?: string;
    isLoading: boolean;
    hasCompletedResult: boolean;
    reviewStatuses: Record<string, AiRelationReviewStatus>;
    reviewSelections: AiRelationReviewSelection;
    onLoad: () => void;
    onExtract: () => void;
    onReviewRelations: (decisions: AiRelationReviewDecision[]) => Promise<void>;
    onSelectRelation: (selectionKey: string, selected: boolean) => void;
    onClose: () => void;
}) {
    if (view?.result) {
        return <div className="ai-extraction-history-result">
            {extractionState?.status === 'error' && <div className="ai-history-warning">
                <AlertTriangle size={15}/>
                <div>
                    <strong>本次提取失败，已显示上一次成功结果。</strong>
                    <details>
                        <summary>查看原因</summary>
                        <p>{extractionState.message || '服务端没有保存本次提取的不完整结果。'}</p>
                    </details>
                </div>
                <button className="secondary-button" type="button" onClick={onExtract}>
                    <Sparkles size={14}/> 重新提取
                </button>
            </div>}
            {extractionState?.status === 'processing' && <div className="ai-history-warning processing">
                <LoaderCircle className="spin" size={15}/>
                <div>
                    <strong>新的 AI 提取仍在处理中，当前显示上一次成功结果。</strong>
                </div>
            </div>}
            <AiExtractionPreviewPanel
                extraction={view.result}
                reviewStatuses={reviewStatuses}
                reviewSelections={reviewSelections}
                onReviewRelations={onReviewRelations}
                onSelectRelation={onSelectRelation}
                onClose={onClose}
            />
        </div>;
    }

    if (loadError && hasCompletedResult) {
        return <div className="document-preview-state error ai-output-state"><AlertTriangle size={24}/>
            <h3>上一次成功结果加载失败</h3>
            <p>{loadError}</p>
            <div className="ai-output-state-actions">
                <button className="secondary-button" onClick={onLoad}><LoaderCircle size={14}/> 重新加载结果</button>
                <button className="secondary-button" onClick={onExtract}><Sparkles size={14}/> 重新提取</button>
            </div>
        </div>;
    }

    if (view) {
        return <AiExtractionProgressPanel view={view} onRetry={onExtract}/>;
    }

    if (isLoading || (hasCompletedResult && !loadError)) {
        return <div className="document-preview-state ai-output-state"><LoaderCircle className="spin" size={24}/>
            <h3>正在读取 AI 输出</h3>
            <p>通过抽取运行标识从服务端恢复完整结果和审核状态。</p>
        </div>;
    }

    if (loadError) {
        return <div className="document-preview-state error ai-output-state"><AlertTriangle size={24}/>
            <h3>AI 输出加载失败</h3>
            <p>{loadError}</p>
            <button className="secondary-button" onClick={onLoad}><LoaderCircle size={14}/> 重新加载结果</button>
        </div>;
    }

    if (extractionState?.status === 'processing') {
        return <div className="document-preview-state ai-output-state"><LoaderCircle className="spin" size={24}/>
            <h3>AI 正在提取</h3>
            <p>{extractionState.message || '服务端正在处理来源资料；关闭弹窗不会取消本次运行。'}</p>
        </div>;
    }

    if (extractionState?.status === 'error') {
        return <div className="document-preview-state error ai-output-state"><AlertTriangle size={24}/>
            <h3>最近一次 AI 提取失败</h3>
            <p>{extractionState.message || '服务端没有保存可审核的完整结果。'}</p>
            <button className="secondary-button" onClick={onExtract}><Sparkles size={14}/> 重试提取</button>
        </div>;
    }

    return <div className="document-preview-state ai-output-state"><Sparkles size={26}/>
        <h3>尚无 AI 输出</h3>
        <p>点击下方按钮后，实时分片进度、候选关联、证据和冲突会显示在当前 Tab。</p>
        <button className="primary-button" onClick={onExtract}><Sparkles size={14}/> 开始 AI 提取</button>
        <span className="ai-output-boundary">AI 只生成候选事实，关系仍需人工审核。</span>
    </div>;
}

function AiExtractionProgressPanel({
                                       view,
                                       onRetry,
                                   }: {
    view: AiExtractionViewState;
    onRetry: () => void;
}) {
    const entities = view.chunks.flatMap((chunk) => chunk.extraction.entities);
    const relations = view.chunks.flatMap((chunk) => chunk.extraction.relations);
    const isFailed = view.status === 'error';
    const streamContentRef = useRef<HTMLDivElement>(null);
    const streamWasNearBottomRef = useRef(true);

    useEffect(() => {
        const content = streamContentRef.current;
        if (!content || !streamWasNearBottomRef.current) return;

        // 只有用户仍停留在内容底部时，才跟随最新完成的分片，避免打断历史内容查看
        content.scrollTo({
            top: content.scrollHeight,
            behavior: 'smooth',
        });
    }, [view.currentChunkId, view.chunks.length]);

    const handleStreamContentScroll = () => {
        const content = streamContentRef.current;
        if (!content) return;
        const distanceToBottom = content.scrollHeight - content.scrollTop - content.clientHeight;
        streamWasNearBottomRef.current = distanceToBottom < 96;
    };

    return <div className="ai-extraction-panel">
            <div className={`ai-stream-status ${isFailed ? 'error' : 'processing'}`}>
                {isFailed ? <AlertTriangle size={16}/> : <LoaderCircle className="spin" size={16}/>}
                <div>
                    <strong>{isFailed ? 'AI 提取失败' : view.message}</strong><span>{isFailed ? view.message : view.currentSectionPath || '等待来源资料分片'}</span>
                </div>
                {isFailed && <button className="secondary-button" onClick={onRetry}><Sparkles size={14}/> 重试提取</button>}
            </div>
            <div className="ai-extraction-summary ai-stream-summary">
                <div>
                    <strong>{view.chunkCount ? `${view.currentChunkIndex}/${view.chunkCount}` : '—'}</strong><span>分片进度</span>
                </div>
                <div><strong>{relations.length}</strong><span>候选关联</span></div>
                <div><strong>{view.chunks.reduce((count, chunk) => count + chunk.extraction.evidences.length, 0)}</strong><span>已校验证据</span></div>
            </div>
            <div
                ref={streamContentRef}
                className="ai-extraction-content ai-stream-content"
                onScroll={handleStreamContentScroll}
            >
                <section className="ai-stream-output">
                    <div className="ai-stream-section-heading"><h3>实时识别进展</h3>
                        <span>{view.currentChunkId || '等待分片'}</span></div>
                    <div className="ai-stream-readable-progress">
                        <strong>{view.currentSectionPath ? `正在识别「${view.currentSectionPath}」` : '正在准备来源资料'}</strong>
                        <p>{view.rawOutput
                            ? `模型正在生成结构化候选，当前分片已接收 ${view.rawOutput.length} 个字符。完整返回并通过校验后，下方会展示可读结果。`
                            : isFailed
                                ? '失败前供应商未返回完整的可校验结果。'
                                : '等待模型返回内容；这里只展示当前运行状态，不使用占位识别结果。'}</p>
                    </div>
                </section>
                {view.chunks.length > 0 && <section className="ai-stream-validated">
                    <div className="ai-stream-section-heading"><h3>已识别并通过校验</h3><span>候选结果，尚未写入正式图谱</span>
                    </div>
                    {view.chunks.map((chunk) => <article className="ai-stream-validated-item" key={chunk.chunkId}>
                        <div>
                            <span>{chunk.sectionPath}</span>
                            <strong>{chunk.extraction.relations.length} 条候选关联
                                · {chunk.extraction.evidences.length} 条证据</strong>
                        </div>
                        <p>{chunk.extraction.summary || '该分片没有返回摘要。'}</p>
                    </article>)}
                </section>}
                {view.rawOutput && <details className="ai-stream-technical-output">
                    <summary>技术详情：运行配置与模型原始 JSON（{view.rawOutput.length} 字符）</summary>
                    <div className="ai-technical-meta">
                        <span>{view.provider && view.model ? `${view.provider} · ${view.model}` : '模型信息等待服务端确认'}</span>
                        <span>{view.promptVersion && view.schemaVersion
                            ? `Prompt ${view.promptVersion} · Schema ${view.schemaVersion}`
                            : 'Prompt 与 Schema 信息等待服务端确认'}</span>
                        <span>{entities.length} 个内部候选实体</span>
                    </div>
                    <pre>{view.rawOutput}</pre>
                </details>}
            </div>
    </div>;
}

function AiExtractionPreviewPanel({
                                      extraction,
                                      reviewStatuses,
                                      reviewSelections,
                                      onReviewRelations,
                                      onSelectRelation,
                                      onClose,
                                  }: {
    extraction: AiDocumentExtraction;
    reviewStatuses: Record<string, AiRelationReviewStatus>;
    reviewSelections: AiRelationReviewSelection;
    onReviewRelations: (decisions: AiRelationReviewDecision[]) => Promise<void>;
    onSelectRelation: (selectionKey: string, selected: boolean) => void;
    onClose: () => void;
}) {
    const entities = extraction.chunks.flatMap((chunk) => chunk.extraction.entities);
    const evidences = extraction.chunks.flatMap((chunk) => chunk.extraction.evidences);
    const conflicts = extraction.chunks.flatMap((chunk) => chunk.extraction.conflicts);
    const entityNames = new Map(entities.map((entity) => [entity.candidateId, entity.name]));
    const relationEntries = extraction.chunks.flatMap((chunk) => {
        const evidenceById = new Map(chunk.extraction.evidences.map((evidence) => [evidence.evidenceId, evidence]));
        return chunk.extraction.relations.map((relation, index) => ({
            key: `${chunk.chunkId}-relation-${index}`,
            chunkId: chunk.chunkId,
            relationIndex: index,
            relation,
            evidences: relation.evidenceIds
                .map((evidenceId) => evidenceById.get(evidenceId))
                .filter((evidence): evidence is NonNullable<typeof evidence> => Boolean(evidence)),
        }));
    });
    const reviewedRelations = relationEntries.filter(({key}) => reviewStatuses[getAiRelationReviewKey(extraction.extractionId, key)]);
    const pendingRelationCount = relationEntries.length - reviewedRelations.length;
    const acceptedRelationCount = relationEntries.filter(({key}) => reviewStatuses[getAiRelationReviewKey(extraction.extractionId, key)] === 'accepted').length;
    const rejectedRelationCount = relationEntries.filter(({key}) => reviewStatuses[getAiRelationReviewKey(extraction.extractionId, key)] === 'rejected').length;
    const pendingRelationKeys = relationEntries
        .map(({key}) => key)
        .filter((key) => !reviewStatuses[getAiRelationReviewKey(extraction.extractionId, key)]);
    const selectedRelationKeys = pendingRelationKeys.filter((key) => reviewSelections[getAiRelationReviewKey(extraction.extractionId, key)]);
    const allPendingRelationsSelected = pendingRelationKeys.length > 0
        && selectedRelationKeys.length === pendingRelationKeys.length;
    const reviewComplete = pendingRelationCount === 0;

    const updateSelectedRelations = (action: AiRelationReviewAction) => {
        const decisions = selectedRelationKeys.map((relationKey) => {
            const relationEntry = relationEntries.find((entry) => entry.key === relationKey);
            return relationEntry && {
                relationKey,
                chunkId: relationEntry.chunkId,
                relationIndex: relationEntry.relationIndex,
                action,
            };
        }).filter((decision): decision is AiRelationReviewDecision => Boolean(decision));
        void onReviewRelations(decisions);
    };

    const toggleAllPendingRelations = () => {
        pendingRelationKeys.forEach((relationKey) => onSelectRelation(
            getAiRelationReviewKey(extraction.extractionId, relationKey),
            !allPendingRelationsSelected
        ));
    };

    return <div className="ai-extraction-panel">
        <div className={`ai-review-status-bar ${reviewComplete ? 'complete' : ''}`}>
            <div><ShieldCheck size={16}/><strong>{reviewComplete ? '关联审核已完成' : '请审核候选关联'}</strong></div>
            <span>待审核 {pendingRelationCount} · 已采纳 {acceptedRelationCount} · 已拒绝 {rejectedRelationCount}</span>
        </div>
        {!reviewComplete && <div className="ai-review-bulk-toolbar">
            <button className={allPendingRelationsSelected ? 'ai-review-select-all selected' : 'ai-review-select-all'}
                aria-pressed={allPendingRelationsSelected} onClick={toggleAllPendingRelations}>
                <Check size={14}/> {allPendingRelationsSelected ? '取消全选' : '全选待审核关联'}
            </button>
            <span className="ai-review-selected-count">已选择 {selectedRelationKeys.length} 条</span>
            <div className="ai-review-bulk-actions">
                <button className="secondary-button" disabled={!selectedRelationKeys.length}
                    onClick={() => updateSelectedRelations('REJECT')}><X size={14}/> 批量拒绝</button>
                <button className="primary-button" disabled={!selectedRelationKeys.length}
                    onClick={() => updateSelectedRelations('ACCEPT')}><Check size={14}/> 批量采纳</button>
            </div>
        </div>}
        <div className="ai-extraction-content ai-review-content">
            {relationEntries.length
                ? <div className="ai-review-relation-list">{relationEntries.map((relationEntry) => {
                    const relationReviewKey = getAiRelationReviewKey(extraction.extractionId, relationEntry.key);
                    const reviewStatus = reviewStatuses[relationReviewKey];
                    const isSelected = Boolean(reviewSelections[relationReviewKey]);
                    return <article
                        className={`ai-review-relation ${reviewStatus ?? 'pending'} ${isSelected ? 'selected' : ''}`}
                        key={relationEntry.key}
                        tabIndex={reviewStatus ? undefined : 0}
                        aria-label={reviewStatus ? undefined : `${isSelected ? '取消选择' : '选择'}关联：${entityNames.get(relationEntry.relation.sourceEntityId) ?? relationEntry.relation.sourceEntityId} ${formatRelationType(relationEntry.relation.relationType)} ${entityNames.get(relationEntry.relation.targetEntityId) ?? relationEntry.relation.targetEntityId}`}
                        onClick={(event) => {
                            if (reviewStatus || (event.target as HTMLElement).closest('button')) return;
                            onSelectRelation(relationReviewKey, !isSelected);
                        }}
                        onKeyDown={(event) => {
                            if (reviewStatus
                                || (event.target as HTMLElement).closest('button')
                                || (event.key !== 'Enter' && event.key !== ' ')) return;
                            event.preventDefault();
                            onSelectRelation(relationReviewKey, !isSelected);
                        }}
                    >
                        <div className="ai-review-relation-head">
                            <div className="ai-review-relation-main">
                                <div className="ai-review-relation-line">
                                    <strong>{entityNames.get(relationEntry.relation.sourceEntityId) ?? relationEntry.relation.sourceEntityId}</strong>
                                    <span>{formatRelationType(relationEntry.relation.relationType)}</span>
                                    <strong>{entityNames.get(relationEntry.relation.targetEntityId) ?? relationEntry.relation.targetEntityId}</strong>
                                </div>
                            </div>
                            <div className="ai-review-relation-meta">
                                {isSelected && <span className="ai-review-selected-icon" title="已选择"><Check
                                    size={14}/></span>}
                                <em>置信度 {Math.round(relationEntry.relation.confidence * 100)}%</em>
                            </div>
                        </div>
                        {relationEntry.evidences.length > 0
                            ? <div className="ai-review-evidence">{relationEntry.evidences.map((evidence) => <blockquote
                                key={evidence.evidenceId}>“{evidence.quote}”<span>{evidence.sectionPath}</span>
                            </blockquote>)}</div>
                            : <p className="ai-review-no-evidence">没有可显示的原文证据</p>}
                        <div className="ai-review-actions">
                            {reviewStatus
                                ? <span className={`ai-review-decision ${reviewStatus}`}>
                                    {reviewStatus === 'accepted' ? '已采纳关联' : '已拒绝关联'}
                                </span>
                                : <>
                                    <button className="secondary-button" onClick={() => void onReviewRelations([{
                                        relationKey: relationEntry.key,
                                        chunkId: relationEntry.chunkId,
                                        relationIndex: relationEntry.relationIndex,
                                        action: 'REJECT',
                                    }])}><X size={14}/> 拒绝关联</button>
                                    <button className="primary-button" onClick={() => void onReviewRelations([{
                                        relationKey: relationEntry.key,
                                        chunkId: relationEntry.chunkId,
                                        relationIndex: relationEntry.relationIndex,
                                        action: 'ACCEPT',
                                    }])}><Check size={14}/> 采纳关联</button>
                                </>}
                        </div>
                    </article>;
                })}</div>
                : <p className="ai-review-empty">本次没有需要审核的候选关联。</p>}
            {conflicts.length > 0 && <div className="ai-result-section"><h3>检测到的冲突</h3>
                <div className="ai-conflict-list">{conflicts.map((conflict, index) => <div
                    key={`conflict-${index}`}><AlertTriangle size={14}/><span>{conflict.description}</span></div>)}</div>
            </div>}
            <details className="ai-extraction-technical-details">
                <summary>技术详情</summary>
                <div className="ai-technical-meta">
                    <span>{extraction.provider} · {extraction.model}</span>
                    <span>Prompt {extraction.promptVersion} · Schema {extraction.schemaVersion}</span>
                    <span>{extraction.chunkCount} 个分片 · {entities.length} 个内部候选实体 · {evidences.length} 条原文证据</span>
                </div>
                {extraction.chunks.map((chunk) => chunk.extraction.entities.length > 0 && <section
                    className="ai-technical-entity-section" key={chunk.chunkId}>
                    <div><strong>{chunk.sectionPath}</strong><span>{chunk.chunkId}</span></div>
                    <div className="ai-entity-list">{chunk.extraction.entities.map((entity) => <div
                        key={entity.candidateId}><span>{entity.type}</span><strong>{entity.name}</strong>
                        <p>{entity.summary || '暂无摘要'}</p></div>)}</div>
                </section>)}
            </details>
        </div>
        <footer className="ai-review-footer">
            <span>{reviewComplete ? '本次候选关联已全部处理。' : `还有 ${pendingRelationCount} 条候选关联待处理。`}</span>
            <button className="primary-button" disabled={!reviewComplete} onClick={onClose}><Check size={15}/>
                {reviewComplete ? '完成审核并关闭' : '完成审核'}</button>
        </footer>
    </div>;
}

function DocumentSidebar({documents, space}: { documents: SourceDocument[]; space: KnowledgeSpace | null }) {
    const markdownCount = documents.filter((document) => document.kind === 'markdown').length;
    const textCount = documents.filter((document) => document.kind === 'txt').length;
    const pdfCount = documents.filter((document) => document.kind === 'pdf').length;
    const totalSize = documents.reduce((sum, document) => sum + (document.fileSize ?? 0), 0);

    return <div className="detail-content document-sidebar">
        <div className="detail-kicker"><span className="type-dot" style={{background: '#94a3b8'}}/>来源资料<span
            className="status-pill active">服务端持久化</span></div>
        <h2>{space?.name ?? '未连接空间'}</h2>
        <p className="detail-summary">原始文件保存在当前知识空间独立目录中，SQLite
            只保存结构化索引、解析文本和证据定位。</p>
        <div className="document-summary-grid">
            <div><strong>{documents.length}</strong><span>已加载资料</span></div>
            <div><strong>{markdownCount}</strong><span>Markdown</span></div>
            <div><strong>{textCount}</strong><span>TXT</span></div>
            <div><strong>{pdfCount}</strong><span>PDF</span></div>
            <div><strong>{formatFileSize(totalSize)}</strong><span>文件总量</span></div>
        </div>
        <div className="detail-block">
            <div className="detail-label">当前阶段边界</div>
            <div className="boundary-list"><span>✓ UTF-8 与文本型 PDF 解析</span><span>✓ PDF 页码边界可反查</span><span>✓ SHA-256 空间内去重</span><span>○ 扫描 PDF 暂不支持 OCR</span>
            </div>
        </div>
    </div>;
}

function NodeDetail({node, edges, graph, onSelectNode}: {
    node: GraphNode;
    edges: GraphEdge[];
    graph: GraphData;
    onSelectNode: (id: string) => void
}) {
    return <div className="detail-content">
        <div className="detail-kicker"><span className="type-dot"
            style={{background: nodeTypeColors[node.type]}}/>{nodeTypeLabels[node.type]}<span
            className={`status-pill ${node.status}`}>{node.status === 'conflict' ? '需要关注' : node.status === 'orphan' ? '未归档' : node.status === 'completed' ? '已完成' : '进行中'}</span>
        </div>
        <h2>{node.label}</h2><p className="detail-summary">{node.summary}</p>
        <div className="detail-block">
            <div className="detail-label">关系上下文 <span>{edges.length}</span></div>
            <div className="relation-list">{edges.map((edge) => {
                const otherId = edge.source === node.id ? edge.target : edge.source;
                const other = graph.nodes.find((item) => item.id === otherId);
                return <button key={edge.id} className="relation-row" onClick={() => onSelectNode(otherId)}><span
                    className="relation-node"><span className="type-dot"
                    style={{background: other ? nodeTypeColors[other.type] : '#64748b'}}/>{other?.label ?? otherId}</span><span
                    className="relation-type">{edge.type}<em
                    className={edge.status}>{formatStatus(edge.status)}</em></span><ChevronRight size={15}/></button>;
            })}</div>
        </div>
        <div className="detail-block">
            <div className="detail-label">来源资料 <span>{node.sourceIds.length}</span></div>
            <div className="source-list">{node.sourceIds.length ? node.sourceIds.map((id) => {
                const document = graph.documents.find((item) => item.id === id);
                return <div className="source-row" key={id}><FileText size={15}/><span>{document?.name ?? id}</span>
                </div>;
            }) : <div className="muted-row">暂无来源，建议补充原始资料</div>}</div>
        </div>
        {edges.find((edge) => edge.evidence.length)?.evidence[0] && <div className="evidence-box">
            <div className="evidence-title"><ShieldCheck size={15}/> 关系依据</div>
            <p>“{edges.find((edge) => edge.evidence.length)?.evidence[0].quote}”</p>
            <span>{edges.find((edge) => edge.evidence.length)?.evidence[0].sourceDocumentName} · {edges.find((edge) => edge.evidence.length)?.evidence[0].locator}</span>
        </div>}
    </div>;
}

function HealthPanel({graph, onSelectNode}: { graph: GraphData; onSelectNode: (id: string) => void }) {
    const confirmed = graph.edges.filter((edge) => edge.status === 'confirmed');
    const connected = new Set(confirmed.flatMap((edge) => [edge.source, edge.target]));
    const orphanNodes = graph.nodes.filter((node) => node.status === 'orphan' || !connected.has(node.id));
    const conflictNodes = graph.nodes.filter((node) => node.status === 'conflict');
    const suggested = graph.edges.filter((edge) => edge.status === 'suggested');
    const issues = [{
        title: '待审核关联',
        count: suggested.length,
        tone: 'warning',
        icon: Inbox,
        items: suggested.map((edge) => ({
            id: edge.source,
            label: `${graph.nodes.find((node) => node.id === edge.source)?.label} → ${graph.nodes.find((node) => node.id === edge.target)?.label}`
        }))
    }, {
        title: '孤立或未归档节点',
        count: orphanNodes.length,
        tone: 'danger',
        icon: Link2,
        items: orphanNodes.map((node) => ({id: node.id, label: node.label}))
    }, {
        title: '可能存在冲突',
        count: conflictNodes.length,
        tone: 'danger',
        icon: AlertTriangle,
        items: conflictNodes.map((node) => ({id: node.id, label: node.label}))
    }];
    return <div className="health-grid">{issues.map((issue) => <article className="health-card" key={issue.title}>
        <div className={`health-icon ${issue.tone}`}>
            <issue.icon size={18}/>
        </div>
        <div className="health-card-heading">
            <div><h3>{issue.title}</h3><p>{issue.count ? '建议现在处理' : '当前没有发现问题'}</p></div>
            <strong>{issue.count}</strong></div>
        {issue.items.length > 0 &&
            <div className="health-items">{issue.items.map((item) => <button key={item.id + item.label}
                onClick={() => onSelectNode(item.id)}>{item.label}<ChevronRight size={15}/></button>)}</div>}
    </article>)}</div>;
}
