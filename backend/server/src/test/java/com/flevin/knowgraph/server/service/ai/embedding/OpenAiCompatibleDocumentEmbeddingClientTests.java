package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI-compatible 真实 Embedding 客户端单元测试，不发起外部网络请求。
 */
class OpenAiCompatibleDocumentEmbeddingClientTests {

    private static final EmbeddingModelDescriptor DESCRIPTOR = new EmbeddingModelDescriptor(
            "openai-compatible",
            "text-embedding-3-small",
            "openai-v1",
            4
    );

    @Test
    void embedsTextsInOrderAndReturnsVectorsMatchingDescriptor() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                new Embedding(new float[]{1F, 0F, 0F, 0F}),
                new Embedding(new float[]{0F, 1F, 0F, 0F})
        )));
        OpenAiCompatibleDocumentEmbeddingClient client =
                new OpenAiCompatibleDocumentEmbeddingClient(embeddingModel, DESCRIPTOR);

        List<EmbeddingVector> vectors = client.embed(List.of("虚构分片一", "虚构分片二"));

        // 客户端按输入顺序返回向量，且描述快照来自显式配置
        assertThat(client.descriptor()).isEqualTo(DESCRIPTOR);
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0).values()).containsExactly(1F, 0F, 0F, 0F);
        assertThat(vectors.get(1).values()).containsExactly(0F, 1F, 0F, 0F);
    }

    @Test
    void rejectsCountMismatchBeforeReturningVectors() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                new Embedding(new float[]{1F, 0F, 0F, 0F})
        )));
        OpenAiCompatibleDocumentEmbeddingClient client =
                new OpenAiCompatibleDocumentEmbeddingClient(embeddingModel, DESCRIPTOR);

        // 返回数量与输入不一致时整批失败，避免半批向量进入事实库
        assertThatThrownBy(() -> client.embed(List.of("虚构分片一", "虚构分片二")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数量");
    }

    @Test
    void rejectsDimensionMismatchWithConfiguredDescriptor() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                new Embedding(new float[]{1F, 0F})
        )));
        OpenAiCompatibleDocumentEmbeddingClient client =
                new OpenAiCompatibleDocumentEmbeddingClient(embeddingModel, DESCRIPTOR);

        // 维度与显式配置不一致时失败，并提示检查 AI_EMBEDDING_DIMENSION / AI_EMBEDDING_VERSION
        assertThatThrownBy(() -> client.embed(List.of("虚构分片一")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度")
                .hasMessageContaining("AI_EMBEDDING_DIMENSION");
    }

    @Test
    void rejectsBlankTextsAndEmptyBatches() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        OpenAiCompatibleDocumentEmbeddingClient client =
                new OpenAiCompatibleDocumentEmbeddingClient(embeddingModel, DESCRIPTOR);

        assertThatThrownBy(() -> client.embed(List.of("虚构分片一", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> client.embed(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空批次");
    }

    @Test
    void passesSegmentsToModelInInputOrder() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                new Embedding(new float[]{1F, 0F, 0F, 0F})
        )));
        OpenAiCompatibleDocumentEmbeddingClient client =
                new OpenAiCompatibleDocumentEmbeddingClient(embeddingModel, DESCRIPTOR);

        client.embed(List.of("虚构分片一"));

        // 验证传给模型的分段与输入文本一一对应
        verify(embeddingModel).embedAll(List.of(TextSegment.from("虚构分片一")));
    }
}
