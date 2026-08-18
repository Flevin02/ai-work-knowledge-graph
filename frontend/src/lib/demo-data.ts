import type { GraphData } from './types';

const date = '2026-08-17T09:00:00.000Z';

export const demoGraph: GraphData = {
  nodes: [
    { id: 'project-annual-party', type: 'project', label: '2026 年公司年会', summary: '面向全体员工的年度活动，当前处于筹备阶段。', status: 'active', sourceIds: ['doc-plan', 'doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'department-admin', type: 'department', label: '行政部', summary: '负责年会整体统筹和供应商协调。', status: 'active', sourceIds: ['doc-role'], createdAt: date, updatedAt: date },
    { id: 'person-zhang', type: 'person', label: '张三', summary: '年会项目负责人，负责场地和流程协调。', status: 'active', sourceIds: ['doc-role', 'doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'person-li', type: 'person', label: '李四', summary: '负责宣传物料和报名信息整理。', status: 'active', sourceIds: ['doc-role'], createdAt: date, updatedAt: date },
    { id: 'meeting-1', type: 'meeting', label: '第一次筹备会议', summary: '确定活动主题、候选场地和分工。', status: 'completed', sourceIds: ['doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'meeting-2', type: 'meeting', label: '第二次筹备会议', summary: '待导入的新资料将补充最终场地和预算结论。', status: 'pending', sourceIds: [], createdAt: date, updatedAt: date },
    { id: 'task-venue', type: 'task', label: '完成场地预订', summary: '确认场地并完成合同签署，截止 8 月 22 日。', status: 'pending', sourceIds: ['doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'task-poster', type: 'task', label: '制作宣传物料', summary: '准备群通知、报名海报和现场指引。', status: 'pending', sourceIds: ['doc-role'], createdAt: date, updatedAt: date },
    { id: 'doc-plan', type: 'document', label: '年会活动方案', summary: '记录活动目标、时间范围和初步流程。', status: 'active', sourceIds: ['doc-plan'], createdAt: date, updatedAt: date },
    { id: 'doc-meeting-1', type: 'document', label: '第一次筹备会议纪要', summary: '记录首次会议决议和任务分工。', status: 'active', sourceIds: ['doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'doc-role', type: 'document', label: '人员分工表', summary: '记录行政部、负责人和宣传物料任务。', status: 'active', sourceIds: ['doc-role'], createdAt: date, updatedAt: date },
    { id: 'doc-contract', type: 'document', label: '场地合同（待确认）', summary: '可能属于年会项目，当前关系等待人工确认。', status: 'orphan', sourceIds: ['doc-contract'], createdAt: date, updatedAt: date },
    { id: 'risk-budget', type: 'risk', label: '预算金额待确认', summary: '会议纪要与报价单中的预算口径尚未统一。', status: 'conflict', sourceIds: ['doc-meeting-1'], createdAt: date, updatedAt: date },
    { id: 'decision-theme', type: 'decision', label: '主题：欢乐同行', summary: '第一次筹备会议已确认年会主题。', status: 'completed', sourceIds: ['doc-meeting-1'], createdAt: date, updatedAt: date },
  ],
  edges: [
    { id: 'edge-1', source: 'department-admin', target: 'project-annual-party', type: '负责统筹', status: 'confirmed', confidence: 0.98, evidence: [{ sourceDocumentId: 'doc-role', sourceDocumentName: '人员分工表', quote: '行政部负责统筹本次年会筹备工作。', locator: '第 1 节', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-2', source: 'person-zhang', target: 'project-annual-party', type: '项目负责人', status: 'confirmed', confidence: 0.96, evidence: [{ sourceDocumentId: 'doc-role', sourceDocumentName: '人员分工表', quote: '张三负责场地、流程和供应商协调。', locator: '分工表第 2 行', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-3', source: 'person-li', target: 'task-poster', type: '负责', status: 'confirmed', confidence: 0.95, evidence: [{ sourceDocumentId: 'doc-role', sourceDocumentName: '人员分工表', quote: '李四负责宣传物料和报名信息整理。', locator: '分工表第 3 行', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-4', source: 'meeting-1', target: 'project-annual-party', type: '属于项目', status: 'confirmed', confidence: 0.99, evidence: [{ sourceDocumentId: 'doc-meeting-1', sourceDocumentName: '第一次筹备会议纪要', quote: '本次会议讨论 2026 年公司年会筹备事项。', locator: '会议主题', extractionMethod: 'rule' }], createdAt: date, updatedAt: date },
    { id: 'edge-5', source: 'doc-plan', target: 'project-annual-party', type: '项目方案', status: 'confirmed', confidence: 0.99, evidence: [{ sourceDocumentId: 'doc-plan', sourceDocumentName: '年会活动方案', quote: '本方案用于指导 2026 年公司年会筹备。', locator: '文档标题和摘要', extractionMethod: 'rule' }], createdAt: date, updatedAt: date },
    { id: 'edge-6', source: 'doc-meeting-1', target: 'meeting-1', type: '会议记录', status: 'confirmed', confidence: 0.99, evidence: [{ sourceDocumentId: 'doc-meeting-1', sourceDocumentName: '第一次筹备会议纪要', quote: '第一次筹备会议纪要', locator: '文档标题', extractionMethod: 'rule' }], createdAt: date, updatedAt: date },
    { id: 'edge-7', source: 'task-venue', target: 'project-annual-party', type: '项目任务', status: 'confirmed', confidence: 0.94, evidence: [{ sourceDocumentId: 'doc-meeting-1', sourceDocumentName: '第一次筹备会议纪要', quote: '张三在 8 月 22 日前完成年会场地预订。', locator: '行动项 1', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-8', source: 'task-poster', target: 'project-annual-party', type: '项目任务', status: 'confirmed', confidence: 0.93, evidence: [{ sourceDocumentId: 'doc-role', sourceDocumentName: '人员分工表', quote: '宣传物料制作属于年会筹备任务。', locator: '分工表第 3 行', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-9', source: 'decision-theme', target: 'project-annual-party', type: '已确认决策', status: 'confirmed', confidence: 0.97, evidence: [{ sourceDocumentId: 'doc-meeting-1', sourceDocumentName: '第一次筹备会议纪要', quote: '会议确认本次年会主题为“欢乐同行”。', locator: '会议决议 1', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-10', source: 'doc-contract', target: 'project-annual-party', type: '可能属于', status: 'suggested', confidence: 0.72, evidence: [{ sourceDocumentId: 'doc-contract', sourceDocumentName: '场地合同（待确认）', quote: '合同日期与年会候选日期接近，但正文未明确写出活动名称。', locator: '合同首页', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
    { id: 'edge-11', source: 'risk-budget', target: 'project-annual-party', type: '存在风险', status: 'confirmed', confidence: 0.88, evidence: [{ sourceDocumentId: 'doc-meeting-1', sourceDocumentName: '第一次筹备会议纪要', quote: '会议纪要记录预算 30,000 元，报价单尚未归档确认。', locator: '风险项 1', extractionMethod: 'ai' }], createdAt: date, updatedAt: date },
  ],
  documents: [
    { id: 'doc-plan', name: '年会活动方案.md', kind: 'markdown', contentHash: 'sha256-demo-plan', excerpt: '本方案用于指导 2026 年公司年会筹备，活动预计在 10 月举行。', status: 'active', importedAt: date, updatedAt: date },
    { id: 'doc-meeting-1', name: '第一次筹备会议纪要.md', kind: 'markdown', contentHash: 'sha256-demo-meeting-1', excerpt: '会议确认主题、候选场地和首批行动项。', status: 'active', importedAt: date, updatedAt: date },
    { id: 'doc-role', name: '人员分工表.txt', kind: 'txt', contentHash: 'sha256-demo-role', excerpt: '行政部统筹，张三负责场地，李四负责宣传物料。', status: 'active', importedAt: date, updatedAt: date },
    { id: 'doc-contract', name: '场地合同.docx', kind: 'docx', contentHash: 'sha256-demo-contract', excerpt: '合同双方和日期已识别，但尚未确认属于年会项目。', status: 'active', importedAt: date, updatedAt: date },
  ],
};
