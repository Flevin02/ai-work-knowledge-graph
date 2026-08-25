'use client';

import { FileText, Link2 } from 'lucide-react';
import type { GraphData } from '@/lib/types';

/**
 * 文档关系图的最小详情侧栏，只展示当前图的真实来源和确认边界。
 */
export default function DocumentGraphSidebar({graph}: {graph: GraphData}) {
  return <div className="detail-content">
    <div className="detail-kicker"><span className="type-dot" style={{background: '#94a3b8'}}/>文档关系图
      <span className="status-pill confirmed">已确认关系</span>
    </div>
    <h2>真实来源文档</h2>
    <p className="detail-summary">当前视图只读取有效 source_documents 和 confirmed document_relations，不混入历史实体图谱。</p>
    <div className="document-summary-grid">
      <div><strong>{graph.nodes.length}</strong><span>文档节点</span></div>
      <div><strong>{graph.edges.length}</strong><span>确认关系</span></div>
    </div>
    <div className="detail-block">
      <div className="detail-label">图谱边界</div>
      <div className="boundary-list">
        <span><FileText size={14}/> 节点来自真实来源资料</span>
        <span><Link2 size={14}/> 边只显示 confirmed 关系</span>
        <span>○ 详情跳转和证据定位将在后续切片补充</span>
      </div>
    </div>
  </div>;
}
