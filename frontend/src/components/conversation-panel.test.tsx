import {render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {afterEach, describe, expect, it, vi} from 'vitest';

import ConversationPanel from '@/components/conversation-panel';
import type {Conversation, ConversationDetail, ConversationMessage, SourceDocument} from '@/lib/types';

const conversationApi = vi.hoisted(() => ({
  createConversation: vi.fn(),
  getConversation: vi.fn(),
  submitConversationMessage: vi.fn(),
}));

vi.mock('@/lib/api/conversations', () => conversationApi);

const spaceId = '9007199254740995';
const documentId = '9007199254740997';
const conversationId = '9007199254740993';

const documents: SourceDocument[] = [{
  id: documentId,
  spaceId,
  name: '2026 年会活动方案.md',
  kind: 'markdown',
  documentType: 'prd',
  contentHash: 'hash-1',
  excerpt: '年会活动方案摘要',
  status: 'active',
  fileSize: 1200,
  importedAt: '2026-09-02T01:00:00Z',
  updatedAt: '2026-09-02T01:00:00Z',
}];

const conversation: Conversation = {
  conversationId,
  spaceId,
  title: '年会场地在哪里？',
  scopeDocumentId: documentId,
  status: 'active',
  createdAt: '2026-09-02T01:00:00Z',
  updatedAt: '2026-09-02T01:00:00Z',
};

const assistantMessage: ConversationMessage = {
  messageId: '9007199254740999',
  conversationId,
  role: 'assistant',
  content: '年会场地位于云栖会展中心。',
  status: 'completed',
  groundingStatus: 'grounded',
  errorCategory: null,
  errorMessage: null,
  answerClient: 'openai-compatible',
  promptVersion: 'conversation-answer-v1',
  schemaVersion: 'conversation-answer-schema-v1',
  citationCount: 1,
  citationFailureCount: 0,
  durationMs: 1200,
  createdAt: '2026-09-02T01:01:00Z',
  citations: [{
    citationId: '9007199254741001',
    messageId: '9007199254740999',
    sourceDocumentId: documentId,
    sourceDocumentName: '2026 年会活动方案.md',
    sourceStale: false,
    chunkRecordId: '9007199254741003',
    chunkId: 'chunk-3',
    sectionPath: '场地安排',
    quote: '年会场地位于云栖会展中心。',
    startOffset: 12,
    endOffset: 27,
    citationOrder: 1,
    validationStatus: 'verified',
  }],
};

const insufficientEvidenceMessage: ConversationMessage = {
  ...assistantMessage,
  messageId: '9007199254741011',
  content: '当前资料不足，无法回答这个问题。',
  groundingStatus: 'insufficient_evidence',
  citationCount: 0,
  citations: [],
};

const failedMessage: ConversationMessage = {
  ...assistantMessage,
  messageId: '9007199254741012',
  content: '回答生成失败',
  status: 'failed',
  groundingStatus: null,
  errorCategory: 'answer_client_unavailable',
  errorMessage: '问答模型未启用',
  citationCount: 0,
  citations: [],
};

const staleCitationMessage: ConversationMessage = {
  ...assistantMessage,
  messageId: '9007199254741013',
  groundingStatus: 'partially_grounded',
  citationFailureCount: 1,
  citations: [{
    ...assistantMessage.citations[0],
    citationId: '9007199254741014',
    messageId: '9007199254741013',
    sourceStale: true,
    validationStatus: 'stale',
  }],
};

function userMessage(content: string): ConversationMessage {
  return {
    messageId: '9007199254740998',
    conversationId,
    role: 'user',
    content,
    status: 'completed',
    groundingStatus: null,
    errorCategory: null,
    errorMessage: null,
    answerClient: null,
    promptVersion: null,
    schemaVersion: null,
    citationCount: 0,
    citationFailureCount: 0,
    durationMs: null,
    createdAt: '2026-09-02T01:00:30Z',
    citations: [],
  };
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('ConversationPanel', () => {
  it('提交首个问题时创建文档范围会话并展示带验证引用的回答', async () => {
    conversationApi.createConversation.mockResolvedValue(conversation);
    conversationApi.submitConversationMessage.mockResolvedValue(assistantMessage);
    const onConversationReady = vi.fn();
    const onOpenCitation = vi.fn();

    render(
      <ConversationPanel
        spaceId={spaceId}
        documents={documents}
        onConversationReady={onConversationReady}
        onOpenCitation={onOpenCitation}
      />,
    );

    await userEvent.type(screen.getByLabelText('输入问题'), '年会场地在哪里？');
    await userEvent.click(screen.getByRole('button', {name: '提交问题'}));

    await waitFor(() => expect(conversationApi.createConversation).toHaveBeenCalledWith(spaceId, {
      title: '年会场地在哪里？',
      scopeDocumentId: documentId,
    }));
    expect(conversationApi.submitConversationMessage).toHaveBeenCalledWith(
      spaceId,
      conversationId,
      '年会场地在哪里？',
    );
    expect(onConversationReady).toHaveBeenCalledWith(conversationId);
    expect(await screen.findByText('年会场地在哪里？')).toBeInTheDocument();
    expect(screen.getByText('年会场地位于云栖会展中心。')).toBeInTheDocument();
    expect(screen.getByText('证据充分')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', {name: '打开引用：2026 年会活动方案.md / 场地安排'}));

    expect(onOpenCitation).toHaveBeenCalledWith(assistantMessage.citations[0]);
  });

  it('按会话标识恢复历史消息并展示证据不足和失败状态', async () => {
    const detail: ConversationDetail = {
      conversation,
      messages: [
        userMessage('预算上限是多少？'),
        insufficientEvidenceMessage,
        failedMessage,
      ],
    };
    conversationApi.getConversation.mockResolvedValue(detail);

    render(
      <ConversationPanel
        spaceId={spaceId}
        documents={documents}
        initialConversationId={conversationId}
        onConversationReady={vi.fn()}
        onOpenCitation={vi.fn()}
      />,
    );

    expect(screen.getByText('正在恢复问答会话…')).toBeInTheDocument();
    expect(await screen.findByText('预算上限是多少？')).toBeInTheDocument();
    expect(screen.getByText('当前资料不足，无法回答这个问题。')).toBeInTheDocument();
    expect(screen.getByText('资料不足')).toBeInTheDocument();
    expect(screen.getByText('问答模型未启用')).toBeInTheDocument();
    expect(screen.getByText('回答失败')).toBeInTheDocument();
    expect(conversationApi.getConversation).toHaveBeenCalledWith(spaceId, conversationId);
  });

  it('失效引用只展示来源变化提示并禁止打开新版本', async () => {
    conversationApi.getConversation.mockResolvedValue({
      conversation,
      messages: [userMessage('场地后来变了吗？'), staleCitationMessage],
    } satisfies ConversationDetail);

    render(
      <ConversationPanel
        spaceId={spaceId}
        documents={documents}
        initialConversationId={conversationId}
        onConversationReady={vi.fn()}
        onOpenCitation={vi.fn()}
      />,
    );

    const staleCitation = await screen.findByRole('button', {
      name: '引用已失效：2026 年会活动方案.md / 场地安排',
    });

    expect(staleCitation).toBeDisabled();
    expect(screen.getByText('来源版本已变化，不能打开新版本')).toBeInTheDocument();
    expect(screen.getByText('部分证据')).toBeInTheDocument();
  });
});
