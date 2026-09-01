package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingDocumentContext;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAI-compatible 文档标签客户端单元测试，不发起外部网络请求。
 */
class OpenAiCompatibleDocumentTaggingClientTests {

    private static final String VALID_JSON = """
            {"summary":"这是一份虚构的年会筹备纪要。",\
            "tags":[{"candidateId":"t1","name":"年会筹备","confidence":0.9,\
            "evidenceIds":["e1"]}],\
            "evidences":[{"evidenceId":"e1","sourceDocumentId":201,"chunkId":"c-1",\
            "sectionPath":"概述","quote":"虚构原文片段"}]}""";

    @Test
    void rendersDocumentContextAndParsesModelResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(VALID_JSON));
        OpenAiCompatibleDocumentTaggingClient client =
                new OpenAiCompatibleDocumentTaggingClient(chatModel);

        DocumentTaggingResult result = client.tag(request());

        // 模型输出解析为结构化结果：摘要、标签候选与逐字证据各就其位
        assertThat(result.summary()).isEqualTo("这是一份虚构的年会筹备纪要。");
        assertThat(result.tags()).hasSize(1);
        assertThat(result.tags().getFirst().name()).isEqualTo("年会筹备");
        assertThat(result.tags().getFirst().evidenceIds()).containsExactly("e1");
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().chunkId()).isEqualTo("c-1");
    }

    @Test
    void rejectsNullRequestBeforeRemoteCall() {
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiCompatibleDocumentTaggingClient client =
                new OpenAiCompatibleDocumentTaggingClient(chatModel);

        // 空请求在产生远程调用前拒绝，避免无效模型调用
        assertThatThrownBy(() -> client.tag(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("文档标签请求不能为空");
    }

    /**
     * 构造包装固定 JSON 文本的聊天响应。
     *
     * @param json 模型返回的 JSON 文本
     * @return 聊天响应
     */
    private ChatResponse response(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }

    /**
     * 构建带单个分片的虚构标签请求。
     *
     * @return 标签请求
     */
    private DocumentTaggingRequest request() {
        DocumentChunk chunk = new DocumentChunk(
                "c-1",
                "section-1",
                "概述",
                1,
                "虚构原文片段",
                0,
                6
        );
        DocumentTaggingDocumentContext document = new DocumentTaggingDocumentContext(
                201L,
                "当前文档.md",
                "markdown",
                "general",
                "hash-current",
                List.of(chunk)
        );
        return new DocumentTaggingRequest(
                1L,
                document,
                "document-tag-v1",
                "document-tag-v1"
        );
    }
}
