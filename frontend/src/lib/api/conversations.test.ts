import {afterEach, describe, expect, it, vi} from 'vitest';

import {
  createConversation,
  getConversation,
  getConversationMessage,
  submitConversationMessage,
} from '@/lib/api/conversations';

const conversation = {
  conversationId: '9007199254740993',
  spaceId: '9007199254740995',
  title: '年会方案答疑',
  scopeDocumentId: '9007199254740997',
  status: 'active',
  createdAt: '2026-09-02T01:00:00Z',
  updatedAt: '2026-09-02T01:00:00Z',
};

const assistantMessage = {
  messageId: '9007199254740999',
  conversationId: conversation.conversationId,
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
    sourceDocumentId: '9007199254740997',
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

function apiResponse<T>(data: T) {
  return new Response(JSON.stringify({
    error: false,
    code: 200,
    msg: '操作成功',
    traceId: 'trace-1',
    data,
  }), {
    status: 200,
    headers: {'Content-Type': 'application/json'},
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('conversation API', () => {
  it('创建会话时编码空间标识并保持文档标识为字符串', async () => {
    let requestUrl = '';
    let requestInit: RequestInit | undefined;
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      requestUrl = String(input);
      requestInit = init;
      return apiResponse(conversation);
    });

    const result = await createConversation('9007199254740995', {
      title: '年会方案答疑',
      scopeDocumentId: '9007199254740997',
    });

    expect(result.conversationId).toBe('9007199254740993');
    expect(result.scopeDocumentId).toBe('9007199254740997');
    expect(requestUrl).toBe('http://localhost:4010/api/v1/spaces/9007199254740995/conversations');
    expect(requestInit?.method).toBe('POST');
    expect(JSON.parse(String(requestInit?.body))).toEqual({
      title: '年会方案答疑',
      scopeDocumentId: '9007199254740997',
    });
  });

  it('恢复会话时返回按服务端顺序排列的完整消息', async () => {
    const userMessage = {
      ...assistantMessage,
      messageId: '9007199254740998',
      role: 'user',
      content: '年会场地在哪里？',
      groundingStatus: null,
      answerClient: null,
      promptVersion: null,
      schemaVersion: null,
      citationCount: 0,
      durationMs: null,
      citations: [],
    };
    vi.stubGlobal('fetch', async () => apiResponse({
      conversation,
      messages: [userMessage, assistantMessage],
    }));

    const result = await getConversation('9007199254740995', '9007199254740993');

    expect(result.messages.map((message) => message.messageId)).toEqual([
      '9007199254740998',
      '9007199254740999',
    ]);
    expect(result.messages[1].citations[0].chunkRecordId).toBe('9007199254741003');
  });

  it('提交问题时发送原问题并返回带验证引用的回答', async () => {
    let requestUrl = '';
    let requestInit: RequestInit | undefined;
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      requestUrl = String(input);
      requestInit = init;
      return apiResponse(assistantMessage);
    });

    const result = await submitConversationMessage(
      '9007199254740995',
      '9007199254740993',
      '年会场地在哪里？',
    );

    expect(requestUrl).toBe(
      'http://localhost:4010/api/v1/spaces/9007199254740995/conversations/9007199254740993/messages',
    );
    expect(JSON.parse(String(requestInit?.body))).toEqual({question: '年会场地在哪里？'});
    expect(result.groundingStatus).toBe('grounded');
    expect(result.citations[0].validationStatus).toBe('verified');
  });

  it('按消息标识恢复单条回答及其引用', async () => {
    let requestUrl = '';
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      requestUrl = String(input);
      return apiResponse(assistantMessage);
    });

    const result = await getConversationMessage(
      '9007199254740995',
      '9007199254740993',
      '9007199254740999',
    );

    expect(requestUrl).toBe(
      'http://localhost:4010/api/v1/spaces/9007199254740995/conversations/9007199254740993/messages/9007199254740999',
    );
    expect(result.messageId).toBe('9007199254740999');
  });
});
