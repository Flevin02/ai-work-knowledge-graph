'use client';

import { ChevronRight, FileText, Link2, MapPin } from 'lucide-react';
import type { DocumentGraphData } from '@/lib/types';

/**
 * 文档关系图的最小详情侧栏，只展示当前图的真实来源和确认边界。
 */
export default function DocumentGraphSidebar({
                                                   graph,
                                                   selectedNodeId,
                                                   onOpenDocument,
                                                   onOpenEvidence,
                                               }: {
    graph: DocumentGraphData;
    selectedNodeId: string | null;
    onOpenDocument: (nodeId: string) => void;
    onOpenEvidence: (documentId: string, evidence: DocumentGraphData['edges'][number]['evidences'][number]) => void;
}) {
  const selectedNode = graph.nodes.find((node) => node.id === selectedNodeId);
  const selectedEdges = selectedNode
      ? graph.edges.filter((edge) => edge.sourceDocumentId === selectedNode.id || edge.targetDocumentId === selectedNode.id)
      : [];

  return <div className="detail-content">
    <div className="detail-kicker"><span className="type-dot" style={{background: '#94a3b8'}}/>文档关系图
      <span className="status-pill confirmed">已确认关系</span>
    </div>
    <h2>{selectedNode?.name ?? '真实来源文档'}</h2>
    <p className="detail-summary">{selectedNode?.summary || '当前视图只读取有效 source_documents 和 confirmed document_relations，不混入历史实体图谱。'}</p>
    <div className="document-summary-grid">
      <div><strong>{selectedNode ? selectedEdges.length : graph.nodes.length}</strong><span>{selectedNode ? '相关关系' : '文档节点'}</span></div>
      <div><strong>{selectedNode ? selectedEdges.reduce((count, edge) => count + edge.evidences.length, 0) : graph.edges.length}</strong><span>{selectedNode ? '可定位证据' : '确认关系'}</span></div>
    </div>
    {selectedNode && <>
      <button className="primary-button document-graph-open-button" type="button" onClick={() => onOpenDocument(selectedNode.id)}>
        <FileText size={14}/> 查看文档详情 <ChevronRight size={14}/>
      </button>
      <div className="detail-block">
        <div className="detail-label">关联关系 <span>{selectedEdges.length}</span></div>
        {selectedEdges.length
            ? <div className="relation-list">{selectedEdges.map((edge) => {
                const otherId = edge.sourceDocumentId === selectedNode.id ? edge.targetDocumentId : edge.sourceDocumentId;
                const other = graph.nodes.find((node) => node.id === otherId);
                return <article className="document-graph-relation" key={edge.id}>
                  <button className="relation-row" type="button" onClick={() => onOpenDocument(otherId)}>
                    <span className="relation-node"><FileText size={14}/>{other?.name ?? '未知文档'}</span>
                    <span className="relation-type">{edge.relationType}<em className="confirmed">已确认</em></span>
                    <ChevronRight size={15}/>
                  </button>
                  {edge.reason && <p className="document-graph-reason">{edge.reason}</p>}
                  {edge.evidences.map((evidence) => <button
                      className="document-graph-evidence"
                      type="button"
                      key={evidence.id}
                      onClick={() => onOpenEvidence(evidence.sourceDocumentId, evidence)}
                  ><MapPin size={13}/><span>{evidence.sectionPath || '原文证据'}：{evidence.quote}</span></button>)}
                </article>;
            })}</div>
            : <p className="detail-summary">当前关系没有可定位证据。</p>}
      </div>
    </>}
    <div className="detail-block">
      <div className="detail-label">图谱边界</div>
      <div className="boundary-list">
        <span><FileText size={14}/> 节点来自真实来源资料</span>
        <span><Link2 size={14}/> 边只显示 confirmed 关系</span>
        <span>✓ 点击节点查看文档详情</span>
        <span>✓ 关系证据可定位到原文预览</span>
      </div>
    </div>
  </div>;
}
