package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.association.DocumentAssociationCandidateContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDocumentContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
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
 * OpenAI-compatible 文档关联判断客户端单元测试，不发起外部网络请求。
 */
class OpenAiCompatibleDocumentAssociationClientTests {

    private static final String VALID_JSON = """
            {"evidences":[{"evidenceId":"e1","sourceDocumentId":201,"chunkId":"c-1",\
            "sectionPath":"概述","quote":"虚构原文片段"}],\
            "decisions":[{"candidateDocumentId":202,"relationType":"related_to",\
            "direction":"symmetric","confidence":0.8,"reason":"主题一致",\
            "matchedTagIds":[],"evidenceIds":["e1"]}]}""";

    private static final String MISSING_DECISIONS_JSON = """
            {"evidences":[{"evidenceId":"e1","sourceDocumentId":201,"chunkId":"c-1",\
            "sectionPath":"概述","quote":"虚构原文片段"}],"decisions":[]}""";

    @Test
    void rendersCandidatesAndParsesModelResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(VALID_JSON));
        OpenAiCompatibleDocumentAssociationClient client =
                new OpenAiCompatibleDocumentAssociationClient(chatModel);

        DocumentAssociationResult result = client.associate(request(1));

        // 模型输出解析为结构化结果，判断与候选一一对应
        assertThat(result.decisions()).hasSize(1);
        assertThat(result.decisions().getFirst().candidateDocumentId()).isEqualTo(202L);
        assertThat(result.decisions().getFirst().relationType()).isEqualTo("related_to");
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceId()).isEqualTo("e1");
    }

    @Test
    void rejectsDecisionCountMismatchBeforeReturningResult() {
        ChatModel chatModel = mock(ChatModel.class);
        // 模型返回空判断列表，但服务端给出了两个候选
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(MISSING_DECISIONS_JSON));
        OpenAiCompatibleDocumentAssociationClient client =
                new OpenAiCompatibleDocumentAssociationClient(chatModel);

        // 判断数量与候选集合不一致时整次失败，交由 Pipeline 记录失败阶段
        assertThatThrownBy(() -> client.associate(request(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数量");
    }

    @Test
    void rejectsEmptyCandidateSetBeforeRemoteCall() {
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiCompatibleDocumentAssociationClient client =
                new OpenAiCompatibleDocumentAssociationClient(chatModel);

        // 空候选在 Pipeline 中已被短路，客户端再次拒绝以避免无效远程调用
        assertThatThrownBy(() -> client.associate(request(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选集合不能为空");
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
     * 构建指定候选数量的虚构关联判断请求。
     *
     * @param candidateCount 候选数量
     * @return 关联判断请求
     */
    private DocumentAssociationRequest request(int candidateCount) {
        DocumentChunk chunk = new DocumentChunk(
                "c-1",
                "section-1",
                "概述",
                1,
                "虚构原文片段",
                0,
                6
        );
        DocumentAssociationDocumentContext current = new DocumentAssociationDocumentContext(
                201L,
                "当前文档.md",
                "markdown",
                "general",
                "hash-current",
                "虚构摘要",
                List.of(),
                List.of(chunk)
        );
        List<DocumentAssociationCandidateContext> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < candidateCount; index++) {
            candidates.add(new DocumentAssociationCandidateContext(
                    new DocumentAssociationDocumentContext(
                            202L + index,
                            "候选" + index + ".md",
                            "markdown",
                            "general",
                            "hash-" + index,
                            "候选摘要",
                            List.of(),
                            List.of(chunk)
                    ),
                    List.of("keyword_match"),
                    List.of(),
                    List.of(),
                    10,
                    index + 1
            ));
        }
        return new DocumentAssociationRequest(
                1L,
                current,
                candidates,
                "document-association-v1",
                "document-association-v1",
                "document-association-policy-v1"
        );
    }
}
