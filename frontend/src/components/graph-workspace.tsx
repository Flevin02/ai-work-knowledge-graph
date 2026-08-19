'use client';

import {useEffect, useMemo, useRef, useState} from 'react';
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
import {
    deleteSourceDocument,
    getDocumentExtraction,
    getSourceDocumentContent,
    importSourceDocuments,
    listDocumentExtractionReviewStates,
    listSourceDocuments,
    reviewDocumentExtractionRelations,
    streamDocumentExtraction
} from '@/lib/api/documents';
import {getGraph} from '@/lib/api/graph';
import {createKnowledgeSpace, deleteKnowledgeSpace, listKnowledgeSpaces} from '@/lib/api/spaces';
import {
    nodeTypeColors,
    nodeTypeLabels,
    type EdgeStatus,
    type AiChunkExtraction,
    type AiDocumentExtraction,
    type AiRelationReviewAction,
    type GraphData,
    type GraphEdge,
    type GraphNode,
    type KnowledgeSpace,
    type NodeType,
    type SourceDocument,
    type SourceDocumentContent,
    type SourceDocumentKind,
} from '@/lib/types';

type GraphWorkspaceProps = { initialGraph: GraphData };
type View = 'graph' | 'documents' | 'health';
type NoticeTone = 'success' | 'warning' | 'error' | 'loading';
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
    | { kind: 'document'; item: SourceDocument };

const allTypes: Array<NodeType | 'all'> = ['all', 'project', 'department', 'person', 'task', 'document', 'meeting', 'risk', 'decision', 'requirement', 'feature'];
const DOCUMENT_PAGE_SIZE = 12;
const DOCUMENT_PROCESSING_POLL_INTERVAL_MS = 3000;
const documentKindLabels: Record<SourceDocumentKind, string> = {
    markdown: 'Markdown',
    txt: 'TXT',
    docx: 'DOCX',
    pdf: 'PDF',
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

function formatStatus(status: EdgeStatus) {
    return {suggested: '待审核', confirmed: '已采纳', rejected: '已拒绝', stale: '已失效'}[status];
}

function formatRelationType(relationType: string) {
    return relationTypeLabels[relationType] ?? '关联';
}

function getAiRelationReviewKey(extractionId: string, relationKey: string) {
    return `${extractionId}:${relationKey}`;
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

export default function GraphWorkspace({initialGraph}: GraphWorkspaceProps) {
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
    const [extractionView, setExtractionView] = useState<AiExtractionViewState | null>(null);
    const [isExtractionViewOpen, setIsExtractionViewOpen] = useState(false);
    const [deletingDocumentId, setDeletingDocumentId] = useState<string | null>(null);
    const [deleteConfirmation, setDeleteConfirmation] = useState<DeleteConfirmation | null>(null);
    const [aiRelationReviewStatuses, setAiRelationReviewStatuses] = useState<Record<string, AiRelationReviewStatus>>({});
    const [aiRelationReviewSelections, setAiRelationReviewSelections] = useState<AiRelationReviewSelection>({});
    const [documentExtractionStates, setDocumentExtractionStates] = useState<Record<string, DocumentExtractionState>>({});
    const [loadingExtractionResultId, setLoadingExtractionResultId] = useState<string | null>(null);
    const [documentPage, setDocumentPage] = useState(1);
    const [documentTotal, setDocumentTotal] = useState(0);
    const [documentTotalPages, setDocumentTotalPages] = useState(0);
    const [documentRefreshKey, setDocumentRefreshKey] = useState(0);
    const [graphRefreshKey, setGraphRefreshKey] = useState(0);
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
        return () => {
            cancelled = true;
        };
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

    useEffect(() => {
        if (!currentSpaceId) return;
        let cancelled = false;

        const loadGraph = async () => {
            try {
                // 查询当前知识空间真实图谱，候选关系和审核状态均来自服务端
                const loadedGraph = await getGraph(currentSpaceId);
                if (cancelled || (!loadedGraph.nodes.length && !loadedGraph.edges.length)) return;
                setGraph((current) => ({...loadedGraph, documents: current.documents}));
                setSelectedNodeId((current) => loadedGraph.nodes.some((node) => node.id === current)
                    ? current
                    : loadedGraph.nodes[0]?.id ?? null);
            } catch (error) {
                if (cancelled) return;
                setNotice(`真实图谱加载失败，暂保留当前演示图谱：${error instanceof Error ? error.message : '未知错误'}`);
                setNoticeTone('warning');
            }
        };

        void loadGraph();
        return () => {
            cancelled = true;
        };
    }, [currentSpaceId, graphRefreshKey]);

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
    const confirmedEdgeCount = graph.edges.filter((edge) => edge.status === 'confirmed').length;

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
                : '本次 AI 抽取的关联审核已完成，真实图谱已更新。');
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
                const nextStates = {...current};
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
        setIsExtractionViewOpen(true);
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
                        message: '完整结果已通过校验并保存',
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
            setIsExtractionViewOpen(true);
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
        setGraph({...initialGraph, documents: mergeDocuments(initialGraph.documents, persistedDocuments)});
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
            : '知识健康检查';
    const pageDescription = view === 'graph'
        ? '从项目、任务、人员和资料之间的关系中找到工作上下文。'
        : view === 'documents'
            ? '查看当前知识空间中真实持久化的来源文件、解析状态和文本预览。'
            : '先处理会影响知识可信度的问题，再继续扩展图谱。';

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
                    <span className="local-badge"><span className="status-dot"/> 本地数据模式</span>
                    <button className="ghost-button" onClick={resetDemo}>恢复演示资料</button>
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
                        <div className="space-switcher">
                            <select aria-label="选择知识空间" value={currentSpaceId ?? ''} onChange={(event) => {
                                setDocumentPage(1);
                                setCurrentSpaceId(event.target.value);
                            }} disabled={!spaces.length || isManagingSpace}>
                                {!spaces.length && <option value="">后端未连接</option>}
                                {spaces.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}
                            </select>
                            <button className="space-icon-button" aria-label="新建知识空间" title="新建知识空间"
                                onClick={() => setIsSpaceFormOpen((current) => !current)}><FolderPlus size={15}/>
                            </button>
                            <button className="space-icon-button danger" aria-label="删除当前知识空间"
                                title="删除当前知识空间"
                                disabled={!currentSpace || spaces.length <= 1 || isManagingSpace}
                                onClick={() => currentSpace && setDeleteConfirmation({
                                    kind: 'space',
                                    item: currentSpace
                                })}><Trash2 size={15}/></button>
                        </div>
                        <div className="space-title"><span className="space-icon"><Archive
                            size={17}/></span>{currentSpace?.name ?? '等待后端连接'}</div>
                        <p>{currentSpace?.description ?? '每个知识空间使用独立目录保存来源资料。'}</p>
                        {isSpaceFormOpen && <div className="space-form">
                            <input aria-label="知识空间名称" value={newSpaceName}
                                onChange={(event) => setNewSpaceName(event.target.value)} placeholder="知识空间名称"
                                maxLength={40}/>
                            <input aria-label="知识空间说明" value={newSpaceDescription}
                                onChange={(event) => setNewSpaceDescription(event.target.value)}
                                placeholder="用途说明（可选）" maxLength={200}/>
                            <div className="space-form-actions">
                                <button className="ghost-button" onClick={() => setIsSpaceFormOpen(false)}>取消</button>
                                <button className="primary-button" disabled={isManagingSpace}
                                    onClick={() => void submitNewSpace()}>{isManagingSpace ? '创建中' : '创建'}</button>
                            </div>
                        </div>}
                    </section>

                    <nav className="side-nav" aria-label="主导航">
                        <button className={view === 'graph' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('graph')}><LayoutDashboard
                            size={17}/> 工作图谱 <span>{graph.nodes.length}</span></button>
                        <button className={view === 'documents' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('documents')}><FileText
                            size={17}/> 来源资料 <span>{documentTotal}</span></button>
                        <button className={view === 'health' ? 'nav-item active' : 'nav-item'}
                            onClick={() => setView('health')}><ShieldCheck size={17}/> 知识健康 <span
                            className="warning-count">{issueCount(graph)}</span></button>
                    </nav>

                    <section className="sidebar-section">
                        <div className="section-heading">图谱类型</div>
                        <div className="type-list">
                            {allTypes.map((type) => (
                                <button key={type}
                                    className={typeFilter === type ? 'type-filter selected' : 'type-filter'}
                                    onClick={() => {
                                        setTypeFilter(type);
                                        setView('graph');
                                    }}>
                                    {type === 'all' ? <span className="type-dot all-dot"/> :
                                        <span className="type-dot" style={{background: nodeTypeColors[type]}}/>}
                                    {type === 'all' ? '全部节点' : nodeTypeLabels[type]}
                                    <span>{type === 'all' ? graph.nodes.length : graph.nodes.filter((node) => node.type === type).length}</span>
                                </button>
                            ))}
                        </div>
                    </section>

                    <div className="sidebar-footer"><CircleHelp size={15}/> 关联建议必须有证据，并经过人工审核</div>
                </aside>

                <section className="content-area">
                    <div className="page-heading">
                        <div>
                            <div className="eyebrow">工作台 / {currentSpace?.name ?? '未连接知识空间'}</div>
                            <h1>{pageTitle}</h1><p>{pageDescription}</p></div>
                        <div className="page-stats">
                            <div><strong>{graph.nodes.length}</strong><span>演示节点</span></div>
                            <div><strong>{confirmedEdgeCount}</strong><span>演示关系</span></div>
                            <div><strong>{documentTotal}</strong><span>真实资料</span></div>
                        </div>
                    </div>

                    {notice && <div className={`notice ${noticeTone}`}>{noticeTone === 'loading' ?
                        <LoaderCircle className="spin" size={16}/> : noticeTone === 'error' ?
                            <AlertTriangle size={16}/> : <Check size={16}/>} {notice}
                        <button onClick={() => setNotice('')} aria-label="关闭提示"><X size={15}/></button>
                    </div>}

                    {view === 'graph' && <>
                        <div className="toolbar-card">
                            <div className="search-box"><Search size={17}/><input value={search}
                                onChange={(event) => setSearch(event.target.value)}
                                placeholder="搜索项目、任务、人员或资料"/></div>
                            <div className="toolbar-divider"/>
                            <div className="filter-label"><Filter
                                size={15}/> {typeFilter === 'all' ? '全部类型' : nodeTypeLabels[typeFilter]}</div>
                            <span className="toolbar-hint">实线：已采纳　虚线：待审核　红框：需关注</span>
                        </div>
                        <div className="graph-card"><GraphCanvas nodes={visibleNodes} edges={visibleEdges}
                            selectedNodeId={selectedNodeId} onSelectNode={setSelectedNodeId}/>
                            <div className="graph-footnote">当前视图 {visibleNodes.length} 个节点
                                / {visibleEdges.length} 条关系 · 点击节点查看证据
                            </div>
                        </div>
                    </>}

                    {view === 'documents' && <DocumentPanel
                        documents={persistedDocuments}
                        onPreview={setPreviewDocument}
                        onDelete={(document) => setDeleteConfirmation({kind: 'document', item: document})}
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
                    {view === 'health' && <HealthPanel graph={graph} onSelectNode={(id) => {
                        setSelectedNodeId(id);
                        setView('graph');
                    }}/>}
                </section>

                <aside className="detail-panel">
                    {view === 'documents'
                        ? <DocumentSidebar documents={persistedDocuments} space={currentSpace}/>
                        : selectedNode
                            ? <NodeDetail node={selectedNode} edges={selectedEdges} graph={graph}
                                onSelectNode={setSelectedNodeId}/>
                            : <div className="empty-detail"><Link2 size={28}/><p>选择一个节点查看它的上下文</p></div>}
                </aside>
            </div>
            {previewDocument && currentSpaceId && <DocumentPreviewModal
                document={previewDocument}
                spaceId={currentSpaceId}
                onClose={() => setPreviewDocument(null)}
            />}
            {isExtractionViewOpen && extractionView && <AiExtractionViewModal
                view={extractionView}
                reviewStatuses={aiRelationReviewStatuses}
                reviewSelections={aiRelationReviewSelections}
                onReviewRelations={(decisions) => reviewAiRelations(extractionView.extractionId ?? '', extractionView.documentId, decisions)}
                onSelectRelation={updateAiRelationReviewSelection}
                onClose={() => setIsExtractionViewOpen(false)}
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
        return <div className="state-card document-empty"><FileText size={28}/><h3>尚未导入来源资料</h3>
            <p>点击右上角“导入资料”，选择 UTF-8 Markdown、TXT 或可复制文本 PDF 文件。</p></div>;
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
                    <div className="document-card-head"><span className="document-kind"><FileText
                        size={16}/>{documentKindLabels[document.kind]}</span>
                        <div className="document-status-group"><span
                            className="document-status">已解析</span>{extractionState &&
                            <span className={`ai-document-status ${extractionState.status}`}
                                title={extractionState.message}>{isExtracting && <LoaderCircle className="spin"
                                size={11}/>}{extractionState.status === 'processing' ? 'AI 提取中' : extractionState.status === 'success' ? 'AI 提取完成' : 'AI 提取失败'}</span>}
                        </div>
                    </div>
                    <h3 title={document.name}>{document.name}</h3>
                    <p>{document.excerpt}</p>
                    <div className="document-meta">
                        <span>{formatFileSize(document.fileSize)}</span><span>{formatImportedAt(document.importedAt)}</span>
                    </div>
                    <div className="document-hash" title={document.contentHash}>SHA-256
                        · {document.contentHash.slice(0, 16)}…
                    </div>
                    <div className="document-card-actions">
                        <button className="secondary-button" onClick={() => onPreview(document)}><Eye size={14}/> 查看
                        </button>
                        <button className="secondary-button" disabled={isExtracting}
                            onClick={() => onExtract(document)}>{isExtracting ?
                            <LoaderCircle className="spin" size={14}/> :
                            <Sparkles size={14}/>} {extractionButtonLabel}</button>
                        <span className="result-button-tip" data-tooltip={resultUnavailableMessage}>
            <button className="secondary-button" aria-label={resultUnavailableMessage || resultButtonLabel}
                disabled={!completedExtractionId || isLoadingResult}
                onClick={() => onViewExtraction(document)}>{isLoadingResult ?
                <LoaderCircle className="spin" size={14}/> : <FileText size={14}/>} {resultButtonLabel}</button>
          </span>
                        <button className="secondary-button danger-button"
                            disabled={deletingDocumentId === document.id || isExtracting}
                            onClick={() => onDelete(document)}>{deletingDocumentId === document.id ?
                            <LoaderCircle className="spin" size={14}/> : <Trash2 size={14}/>} 删除
                        </button>
                    </div>
                </article>;
            })}
        </div>
        {totalPages > 1 && <nav className="document-pagination" aria-label="来源资料分页">
            <button className="secondary-button" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
                <ChevronLeft size={15}/> 上一页
            </button>
            <span>第 {page} / {totalPages} 页 · 共 {total} 份</span>
            <button className="secondary-button" disabled={page >= totalPages}
                onClick={() => onPageChange(page + 1)}>下一页 <ChevronRight size={15}/></button>
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
        <section className="confirmation-dialog" role="dialog" aria-modal="true"
            aria-labelledby="delete-confirmation-title" aria-describedby="delete-confirmation-description"
            onClick={(event) => event.stopPropagation()}>
            <header className="confirmation-header">
                <span className="confirmation-icon"><AlertTriangle size={21}/></span>
                <div>
                    <div className="eyebrow">{isSpace ? '知识空间 / 移除确认' : '来源资料 / 删除确认'}</div>
                    <h2 id="delete-confirmation-title">{isSpace ? '确认移除知识空间？' : '确认删除来源资料？'}</h2>
                </div>
                <button className="space-icon-button" aria-label="关闭删除确认" title="关闭" onClick={onCancel}><X
                    size={16}/></button>
            </header>
            <div className="confirmation-content">
                <div className="confirmation-target">
                    {isSpace ? <Archive size={18}/> : <FileText size={18}/>}
                    <div><span>{isSpace ? '待移除知识空间' : '待删除来源资料'}</span><strong
                        title={target.item.name}>{target.item.name}</strong></div>
                </div>
                <p id="delete-confirmation-description">{isSpace
                    ? '移除后，该空间将不再出现在工作台中；来源资料和图谱事实仍会保留在本地数据库中。'
                    : '删除后，仅由该资料支撑的图谱节点和关系会同步失效；原始文件与历史证据仍会保留。'}</p>
            </div>
            <footer className="confirmation-actions">
                <button className="ghost-button" autoFocus onClick={onCancel}>取消</button>
                <button className="secondary-button danger-button confirmation-submit" onClick={onConfirm}><Trash2
                    size={15}/>{isSpace ? '确认移除' : '确认删除'}</button>
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
    const renderedModeLabel = document.kind === 'pdf' ? '文本预览' : '渲染预览';
    const previewModeLabel = previewMode === 'rendered' ? renderedModeLabel : '原文预览';

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

    return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
        <section className="document-preview-dialog" role="dialog" aria-modal="true"
            aria-labelledby="document-preview-title" onClick={(event) => event.stopPropagation()}>
            <header className="document-preview-header">
                <div>
                    <div className="eyebrow">来源资料 / {previewModeLabel}</div>
                    <h2 id="document-preview-title" title={document.name}>{document.name}</h2>
                </div>
                <button className="space-icon-button" aria-label={`关闭${previewModeLabel}`} title="关闭"
                    onClick={onClose}><X size={16}/></button>
            </header>
            {isLoading && <div className="document-preview-state"><LoaderCircle className="spin"
                size={22}/><span>正在加载原文…</span></div>}
            {!isLoading && error && <div className="document-preview-state error"><AlertTriangle
                size={22}/><span>原文加载失败：{error}</span>
                <button className="secondary-button" onClick={onClose}>关闭</button>
            </div>}
            {!isLoading && !error && content && <>
                <div className="document-preview-meta">
                    <div className="document-preview-mode" role="tablist" aria-label="来源资料预览模式">
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
                    </div>
                    {content.kind === 'pdf' && <span>服务端按页提取文本 · 不包含 OCR</span>}
                    <span>SHA-256 · {content.contentHash.slice(0, 16)}…</span>
                </div>
                {previewMode === 'rendered'
                    ? <article className="document-preview-markdown"><ReactMarkdown
                        remarkPlugins={[remarkGfm]}>{content.contentText}</ReactMarkdown></article>
                    : <pre className="document-preview-content">{content.contentText}</pre>}
            </>}
        </section>
    </div>;
}

function AiExtractionViewModal({
                                   view,
                                   reviewStatuses,
                                   reviewSelections,
                                   onReviewRelations,
                                   onSelectRelation,
                                   onClose,
                               }: {
    view: AiExtractionViewState;
    reviewStatuses: Record<string, AiRelationReviewStatus>;
    reviewSelections: AiRelationReviewSelection;
    onReviewRelations: (decisions: AiRelationReviewDecision[]) => Promise<void>;
    onSelectRelation: (selectionKey: string, selected: boolean) => void;
    onClose: () => void;
}) {
    if (view.result) {
        return <AiExtractionPreviewModal
            extraction={view.result}
            reviewStatuses={reviewStatuses}
            reviewSelections={reviewSelections}
            onReviewRelations={onReviewRelations}
            onSelectRelation={onSelectRelation}
            onClose={onClose}
        />;
    }

    return <AiExtractionProgressModal view={view} onClose={onClose}/>;
}

function AiExtractionProgressModal({
                                       view,
                                       onClose,
                                   }: {
    view: AiExtractionViewState;
    onClose: () => void;
}) {
    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [onClose]);

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

    return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
        <section className="document-preview-dialog ai-extraction-dialog" role="dialog" aria-modal="true"
            aria-labelledby="ai-extraction-progress-title" onClick={(event) => event.stopPropagation()}>
            <header className="document-preview-header">
                <div>
                    <div className="eyebrow">来源资料 / AI 流式提取</div>
                    <h2 id="ai-extraction-progress-title" title={view.documentName}>{view.documentName}</h2>
                </div>
                <button className="space-icon-button" aria-label="关闭 AI 流式提取" title="关闭" onClick={onClose}><X
                    size={16}/></button>
            </header>
            <div className="ai-extraction-meta">
                <span>{view.provider && view.model ? `${view.provider} · ${view.model}` : '正在建立 text/event-stream 连接'}</span>
                <span>{view.promptVersion && view.schemaVersion ? `Prompt ${view.promptVersion} · Schema ${view.schemaVersion}` : '运行标识将在服务端创建后显示'}</span>
            </div>
            <div className={`ai-stream-status ${isFailed ? 'error' : 'processing'}`}>
                {isFailed ? <AlertTriangle size={16}/> : <LoaderCircle className="spin" size={16}/>}
                <div>
                    <strong>{isFailed ? 'AI 提取失败' : view.message}</strong><span>{isFailed ? view.message : view.currentSectionPath || '等待来源资料分片'}</span>
                </div>
            </div>
            <div className="ai-extraction-summary ai-stream-summary">
                <div>
                    <strong>{view.chunkCount ? `${view.currentChunkIndex}/${view.chunkCount}` : '—'}</strong><span>当前分片</span>
                </div>
                <div><strong>{view.chunks.length}</strong><span>已校验分片</span></div>
                <div><strong>{entities.length}</strong><span>候选实体</span></div>
                <div><strong>{relations.length}</strong><span>候选关系</span></div>
                <div><strong>{view.rawOutput.length}</strong><span>当前分片已接收字符</span></div>
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
                                : '等待模型返回内容；这里只展示真实运行状态，不生成虚假识别结果。'}</p>
                    </div>
                </section>
                {view.chunks.length > 0 && <section className="ai-stream-validated">
                    <div className="ai-stream-section-heading"><h3>已识别并通过校验</h3><span>候选结果，尚未写入正式图谱</span>
                    </div>
                    {view.chunks.map((chunk) => <article className="ai-stream-validated-item" key={chunk.chunkId}>
                        <div>
                            <span>{chunk.sectionPath}</span>
                            <strong>{chunk.extraction.entities.length} 个实体 · {chunk.extraction.relations.length} 条关系
                                · {chunk.extraction.evidences.length} 条证据</strong>
                        </div>
                        <p>{chunk.extraction.summary || '该分片没有返回摘要。'}</p>
                        {chunk.extraction.entities.length > 0 && <div className="ai-stream-entity-names">
                            {chunk.extraction.entities.map((entity) => <span key={entity.candidateId}>{entity.name}</span>)}
                        </div>}
                    </article>)}
                </section>}
                {view.rawOutput && <details className="ai-stream-technical-output">
                    <summary>技术详情：查看当前分片的模型原始 JSON（{view.rawOutput.length} 字符）</summary>
                    <pre>{view.rawOutput}</pre>
                </details>}
            </div>
        </section>
    </div>;
}

function AiExtractionPreviewModal({
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
    const relationEntries = extraction.chunks.flatMap((chunk) => chunk.extraction.relations.map((relation, index) => ({
        key: `${chunk.chunkId}-relation-${index}`,
        chunkId: chunk.chunkId,
        relationIndex: index,
        relation,
    })));
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

    return <div className="document-preview-backdrop" role="presentation" onClick={onClose}>
        <section className="document-preview-dialog ai-extraction-dialog" role="dialog" aria-modal="true"
            aria-labelledby="ai-extraction-title" onClick={(event) => event.stopPropagation()}>
            <header className="document-preview-header">
                <div>
                    <div className="eyebrow">来源资料 / AI 结果审核</div>
                    <h2 id="ai-extraction-title" title={extraction.documentName}>{extraction.documentName}</h2>
                </div>
                <button className="space-icon-button" aria-label="关闭 AI 结果审核" title="关闭" onClick={onClose}><X
                    size={16}/></button>
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
            <div className="ai-extraction-content">
                {extraction.chunks.map((chunk) => <section className="ai-chunk-card" key={chunk.chunkId}>
                    <div className="ai-chunk-heading"><span>{chunk.sectionPath}</span><em>{chunk.chunkId}</em></div>
                    <div className="ai-result-section">
                        <h3>候选实体</h3>
                        {chunk.extraction.entities.length
                            ? <div className="ai-entity-list">{chunk.extraction.entities.map((entity) => <div
                                key={entity.candidateId}><span>{entity.type}</span><strong>{entity.name}</strong>
                                <p>{entity.summary || '暂无摘要'}</p></div>)}</div>
                            : <p className="ai-empty-result">当前分片未识别出实体</p>}
                    </div>
                    <div className="ai-result-section">
                        <h3>候选关系</h3>
                        {chunk.extraction.relations.length
                            ? <div className="ai-review-relation-list">{chunk.extraction.relations.map((relation, index) => {
                                const relationKey = `${chunk.chunkId}-relation-${index}`;
                                const relationReviewKey = getAiRelationReviewKey(extraction.extractionId, relationKey);
                                const reviewStatus = reviewStatuses[relationReviewKey];
                                const evidenceById = new Map(chunk.extraction.evidences.map((evidence) => [evidence.evidenceId, evidence]));
                                const relationEvidence = relation.evidenceIds
                                    .map((evidenceId) => evidenceById.get(evidenceId))
                                    .filter((evidence): evidence is NonNullable<typeof evidence> => Boolean(evidence));
                                const isSelected = Boolean(reviewSelections[relationReviewKey]);
                                return <article
                                    className={`ai-review-relation ${reviewStatus ?? 'pending'} ${isSelected ? 'selected' : ''}`}
                                    key={relationKey}
                                    tabIndex={reviewStatus ? undefined : 0}
                                    aria-label={reviewStatus ? undefined : `${isSelected ? '取消选择' : '选择'}关联：${entityNames.get(relation.sourceEntityId) ?? relation.sourceEntityId} ${formatRelationType(relation.relationType)} ${entityNames.get(relation.targetEntityId) ?? relation.targetEntityId}`}
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
                                            <strong>{entityNames.get(relation.sourceEntityId) ?? relation.sourceEntityId}</strong>
                                            <span>{formatRelationType(relation.relationType)}</span>
                                            <strong>{entityNames.get(relation.targetEntityId) ?? relation.targetEntityId}</strong>
                                            </div>
                                        </div>
                                        <div className="ai-review-relation-meta">
                                            {isSelected && <span className="ai-review-selected-icon" title="已选择"><Check
                                                size={14}/></span>}
                                            <em>置信度 {Math.round(relation.confidence * 100)}%</em>
                                        </div>
                                    </div>
                                    {relationEvidence.length > 0
                                        ? <div className="ai-review-evidence">{relationEvidence.map((evidence) => <blockquote
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
                                                    relationKey,
                                                    chunkId: chunk.chunkId,
                                                    relationIndex: index,
                                                    action: 'REJECT',
                                                }])}><X size={14}/> 拒绝关联</button>
                                                <button className="primary-button" onClick={() => void onReviewRelations([{
                                                    relationKey,
                                                    chunkId: chunk.chunkId,
                                                    relationIndex: index,
                                                    action: 'ACCEPT',
                                                }])}><Check size={14}/> 采纳关联</button>
                                            </>}
                                    </div>
                                </article>;
                            })}</div>
                            : <p className="ai-empty-result">当前分片未识别出关系</p>}
                    </div>
                    {chunk.extraction.evidences.length > 0 && <div className="ai-result-section"><h3>原文证据</h3>
                        <div className="ai-evidence-list">{chunk.extraction.evidences.map((evidence) => <blockquote
                            key={evidence.evidenceId}>“{evidence.quote}”<span>{evidence.sectionPath}</span>
                        </blockquote>)}</div>
                    </div>}
                    {chunk.extraction.conflicts.length > 0 && <div className="ai-result-section"><h3>冲突</h3>
                        <div className="ai-conflict-list">{chunk.extraction.conflicts.map((conflict, index) => <div
                            key={`${chunk.chunkId}-conflict-${index}`}><AlertTriangle
                            size={14}/><span>{conflict.description}</span></div>)}</div>
                    </div>}
                </section>)}
            </div>
            <footer className="ai-review-footer">
                <span>{reviewComplete ? '本次候选关联已全部处理。' : `还有 ${pendingRelationCount} 条候选关联待处理。`}</span>
                <button className="primary-button" disabled={!reviewComplete} onClick={onClose}><Check size={15}/>
                    {reviewComplete ? '完成审核并关闭' : '完成审核'}</button>
            </footer>
        </section>
    </div>;
}

function DocumentSidebar({documents, space}: { documents: SourceDocument[]; space: KnowledgeSpace | null }) {
    const markdownCount = documents.filter((document) => document.kind === 'markdown').length;
    const textCount = documents.filter((document) => document.kind === 'txt').length;
    const pdfCount = documents.filter((document) => document.kind === 'pdf').length;
    const totalSize = documents.reduce((sum, document) => sum + (document.fileSize ?? 0), 0);

    return <div className="detail-content document-sidebar">
        <div className="detail-kicker"><span className="type-dot" style={{background: '#94a3b8'}}/>来源资料<span
            className="status-pill active">本地持久化</span></div>
        <h2>{space?.name ?? '未连接空间'}</h2>
        <p className="detail-summary">原始文件保存在当前知识空间独立目录中，SQLite
            只保存结构化索引、解析文本和证据定位。</p>
        <div className="document-summary-grid">
            <div><strong>{documents.length}</strong><span>全部资料</span></div>
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
