'use client';

import {FormEvent, useEffect, useMemo, useState} from 'react';
import {
  AlertTriangle,
  Bot,
  ExternalLink,
  FileText,
  LoaderCircle,
  MessageSquare,
  Send,
  UserRound,
} from 'lucide-react';

import {
  createConversation,
  getConversation,
  submitConversationMessage,
} from '@/lib/api/conversations';
import type {
  Conversation,
  ConversationGroundingStatus,
  ConversationMessage,
  MessageCitation,
  SourceDocument,
} from '@/lib/types';

type ConversationPanelProps = {
  spaceId: string;
  documents: SourceDocument[];
  initialConversationId?: string;
  onConversationReady: (conversationId: string) => void;
  onOpenCitation: (citation: MessageCitation) => void;
};

const groundingStatusLabels: Record<ConversationGroundingStatus, string> = {
  grounded: '证据充分',
  partially_grounded: '部分证据',
  insufficient_evidence: '资料不足',
};

export default function ConversationPanel({
  spaceId,
  documents,
  initialConversationId,
  onConversationReady,
  onOpenCitation,
}: ConversationPanelProps) {
  const activeDocuments = useMemo(
    () => documents.filter((document) => document.status === 'active'),
    [documents],
  );
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState(activeDocuments[0]?.id ?? '');
  const [question, setQuestion] = useState('');
  const [isRestoring, setIsRestoring] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSelectedDocumentId((current) => {
      if (current && activeDocuments.some((document) => document.id === current)) {
        return current;
      }
      return activeDocuments[0]?.id ?? '';
    });
  }, [activeDocuments]);

  useEffect(() => {
    setConversation(null);
    setMessages([]);
    setError(null);
    if (!initialConversationId) return;

    let cancelled = false;
    setIsRestoring(true);

    const restoreConversation = async () => {
      try {
        const detail = await getConversation(spaceId, initialConversationId);
        if (cancelled) return;
        setConversation(detail.conversation);
        setMessages(detail.messages);
        if (detail.conversation.scopeDocumentId) {
          setSelectedDocumentId(detail.conversation.scopeDocumentId);
        }
        onConversationReady(detail.conversation.conversationId);
      } catch (restoreError) {
        if (cancelled) return;
        setError(restoreError instanceof Error ? restoreError.message : '问答会话恢复失败');
      } finally {
        if (!cancelled) setIsRestoring(false);
      }
    };

    void restoreConversation();
    return () => {
      cancelled = true;
    };
  }, [initialConversationId, onConversationReady, spaceId]);

  const selectedDocument = activeDocuments.find((document) => document.id === selectedDocumentId) ?? null;
  const trimmedQuestion = question.trim();
  const canSubmit = Boolean(trimmedQuestion && selectedDocumentId && !isRestoring && !isSubmitting);

  const submitQuestion = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!trimmedQuestion) return;
    if (!selectedDocumentId) {
      setError('请先选择一份来源资料作为问答范围');
      return;
    }

    const submittedQuestion = trimmedQuestion;
    setQuestion('');
    setError(null);
    setIsSubmitting(true);
    setMessages((current) => [...current, buildLocalUserMessage(conversation?.conversationId, submittedQuestion)]);

    try {
      let activeConversation = conversation;
      if (!activeConversation) {
        activeConversation = await createConversation(spaceId, {
          title: submittedQuestion.slice(0, 80),
          scopeDocumentId: selectedDocumentId,
        });
        setConversation(activeConversation);
        onConversationReady(activeConversation.conversationId);
      }

      const answer = await submitConversationMessage(
        spaceId,
        activeConversation.conversationId,
        submittedQuestion,
      );
      setMessages((current) => [...current, answer]);
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : '问答提交失败';
      setMessages((current) => [...current, buildLocalFailedAssistantMessage(conversation?.conversationId, message)]);
      setError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  return <section className="conversation-panel" aria-label="有据问答">
    <div className="conversation-toolbar">
      <div className="conversation-scope">
        <label htmlFor="conversation-scope">问答范围</label>
        <select
          id="conversation-scope"
          value={selectedDocumentId}
          disabled={Boolean(conversation) || isRestoring || isSubmitting || !activeDocuments.length}
          onChange={(event) => setSelectedDocumentId(event.target.value)}
        >
          {activeDocuments.map((document) => (
            <option value={document.id} key={document.id}>{document.name}</option>
          ))}
        </select>
      </div>
      <div className="conversation-scope-summary">
        <FileText size={14}/>
        <span>{selectedDocument ? selectedDocument.name : '先导入来源资料后再提问'}</span>
      </div>
    </div>

    {isRestoring && <div className="conversation-state" role="status">
      <LoaderCircle className="spin" size={18}/>
      <span>正在恢复问答会话…</span>
    </div>}

    {error && <div className="conversation-error" role="alert">
      <AlertTriangle size={15}/>
      <span>{error}</span>
    </div>}

    {!activeDocuments.length ? <div className="state-card conversation-empty">
      <MessageSquare size={28}/>
      <h3>当前没有可问答的来源资料</h3>
      <p>请先导入并解析来源资料，再选择一份文档作为问答范围。</p>
    </div> : <>
      <div className="conversation-messages" aria-live="polite">
        {messages.length ? messages.map((message) => (
          <ConversationMessageItem
            key={message.messageId}
            message={message}
            onOpenCitation={onOpenCitation}
          />
        )) : <div className="conversation-placeholder">
          <MessageSquare size={26}/>
          <span>选择一份来源资料后，可以向当前资料提问。</span>
        </div>}
        {isSubmitting && <div className="conversation-state" role="status">
          <LoaderCircle className="spin" size={18}/>
          <span>正在生成回答…</span>
        </div>}
      </div>

      <form className="conversation-composer" onSubmit={submitQuestion}>
        <textarea
          aria-label="输入问题"
          value={question}
          rows={3}
          maxLength={4000}
          placeholder="输入要基于当前资料回答的问题"
          disabled={isRestoring || isSubmitting}
          onChange={(event) => setQuestion(event.target.value)}
        />
        <button className="primary-button" type="submit" disabled={!canSubmit}>
          {isSubmitting ? <LoaderCircle className="spin" size={15}/> : <Send size={15}/>}
          提交问题
        </button>
      </form>
    </>}
  </section>;
}

function ConversationMessageItem({
  message,
  onOpenCitation,
}: {
  message: ConversationMessage;
  onOpenCitation: (citation: MessageCitation) => void;
}) {
  const isAssistant = message.role === 'assistant';
  const statusLabel = formatMessageStatus(message);

  return <article className={`conversation-message ${message.role} ${message.status}`}>
    <div className="conversation-message-icon" aria-hidden="true">
      {isAssistant ? <Bot size={16}/> : <UserRound size={16}/>}
    </div>
    <div className="conversation-message-body">
      <div className="conversation-message-meta">
        <span>{isAssistant ? '助手回答' : '我的问题'}</span>
        {statusLabel && <strong className={`conversation-status ${message.status}`}>{statusLabel}</strong>}
      </div>
      <p>{message.content}</p>
      {message.errorMessage && <div className="conversation-message-error">{message.errorMessage}</div>}
      {message.citationFailureCount > 0 && <div className="conversation-message-warning">
        已移除 {message.citationFailureCount} 条无法逐字反查的引用
      </div>}
      {message.citations.length > 0 && <div className="conversation-citations">
        {message.citations.map((citation) => (
          <ConversationCitationCard
            citation={citation}
            key={citation.citationId}
            onOpenCitation={onOpenCitation}
          />
        ))}
      </div>}
    </div>
  </article>;
}

function ConversationCitationCard({
  citation,
  onOpenCitation,
}: {
  citation: MessageCitation;
  onOpenCitation: (citation: MessageCitation) => void;
}) {
  const isStale = citation.sourceStale || citation.validationStatus !== 'verified';
  const section = citation.sectionPath || '原文';
  const actionLabel = `${isStale ? '引用已失效' : '打开引用'}：${citation.sourceDocumentName} / ${section}`;

  return <button
    className={`conversation-citation ${isStale ? 'stale' : ''}`}
    type="button"
    aria-label={actionLabel}
    disabled={isStale}
    onClick={() => onOpenCitation(citation)}
  >
    <ExternalLink size={14}/>
    <span>
      <strong>{citation.sourceDocumentName}</strong>
      <em>{section} · “{citation.quote}”</em>
      {isStale && <small>来源版本已变化，不能打开新版本</small>}
    </span>
  </button>;
}

function formatMessageStatus(message: ConversationMessage) {
  if (message.status === 'failed') return '回答失败';
  if (!message.groundingStatus) return null;
  return groundingStatusLabels[message.groundingStatus];
}

function buildLocalUserMessage(conversationId: string | undefined, content: string): ConversationMessage {
  return {
    messageId: `local-user-${Date.now()}`,
    conversationId: conversationId ?? 'pending',
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
    createdAt: new Date().toISOString(),
    citations: [],
  };
}

function buildLocalFailedAssistantMessage(conversationId: string | undefined, errorMessage: string): ConversationMessage {
  return {
    messageId: `local-assistant-${Date.now()}`,
    conversationId: conversationId ?? 'pending',
    role: 'assistant',
    content: '回答生成失败',
    status: 'failed',
    groundingStatus: null,
    errorCategory: 'frontend_submit_failed',
    errorMessage,
    answerClient: null,
    promptVersion: null,
    schemaVersion: null,
    citationCount: 0,
    citationFailureCount: 0,
    durationMs: null,
    createdAt: new Date().toISOString(),
    citations: [],
  };
}
