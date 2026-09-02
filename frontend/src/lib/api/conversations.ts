import {backendApiUrl, readApiResponse} from '@/lib/api/client';
import type {Conversation, ConversationDetail, ConversationMessage} from '@/lib/types';

export type CreateConversationInput = {
  title?: string | null;
  scopeDocumentId: string;
};

export async function createConversation(
  spaceId: string,
  input: CreateConversationInput,
): Promise<Conversation> {
  const response = await fetch(
    `${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/conversations`,
    {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(input),
    },
  );
  return readApiResponse<Conversation>(response);
}

export async function getConversation(
  spaceId: string,
  conversationId: string,
): Promise<ConversationDetail> {
  const response = await fetch(
    `${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/conversations/${encodeURIComponent(conversationId)}`,
    {method: 'GET', cache: 'no-store'},
  );
  return readApiResponse<ConversationDetail>(response);
}

export async function submitConversationMessage(
  spaceId: string,
  conversationId: string,
  question: string,
): Promise<ConversationMessage> {
  const response = await fetch(
    `${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/conversations/${encodeURIComponent(conversationId)}/messages`,
    {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({question}),
    },
  );
  return readApiResponse<ConversationMessage>(response);
}

export async function getConversationMessage(
  spaceId: string,
  conversationId: string,
  messageId: string,
): Promise<ConversationMessage> {
  const response = await fetch(
    `${backendApiUrl}/v1/spaces/${encodeURIComponent(spaceId)}/conversations/${encodeURIComponent(conversationId)}/messages/${encodeURIComponent(messageId)}`,
    {method: 'GET', cache: 'no-store'},
  );
  return readApiResponse<ConversationMessage>(response);
}
