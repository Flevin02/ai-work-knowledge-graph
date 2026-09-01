package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerInvalidOutputException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI-compatible 有据问答客户端单元测试，不发起外部网络请求。
 */
class OpenAiCompatibleConversationAnswerClientTests {

    private static final String VALID_JSON = """
            {"answer":"活动场地位于滨海厅。","citations":[{"chunkId":"chunk-1",\
            "quote":"活动场地位于滨海厅。","startOffset":0,"endOffset":11}]}""";

    @Test
    void rendersBoundedContextAndParsesStructuredAnswer() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response(VALID_JSON));
        OpenAiCompatibleConversationAnswerClient client =
                new OpenAiCompatibleConversationAnswerClient(chatModel);

        ConversationAnswerResult result = client.answer(request());

        // 合法结构化输出映射为供应商无关的回答与候选引用
        assertThat(client.clientId()).isEqualTo("openai-compatible");
        assertThat(result.answer()).isEqualTo("活动场地位于滨海厅。");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().getFirst().chunkId()).isEqualTo("chunk-1");
        assertThat(result.citations().getFirst().quote()).isEqualTo("活动场地位于滨海厅。");

        // 模型输入只使用服务端给定的问题和可引用分片定位，不自行读取事实库
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture());
        UserMessage userMessage = (UserMessage) requestCaptor.getValue().messages().getLast();
        assertThat(userMessage.singleText())
                .contains("年会场地在哪里？")
                .contains("chunk-1")
                .contains("场地安排")
                .contains("活动场地位于滨海厅。");
    }

    @Test
    void rejectsBlankAnswerAsInvalidStructuredOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        // JSON 字段齐全但回答为空，不能作为一条成功回答交给业务层持久化
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {"answer":"  ","citations":[]}
                """));
        OpenAiCompatibleConversationAnswerClient client =
                new OpenAiCompatibleConversationAnswerClient(chatModel);

        // 空回答属于模型结构化输出不合格，而不是连接或供应商服务失败
        assertThatThrownBy(() -> client.answer(request()))
                .isInstanceOf(ConversationAnswerInvalidOutputException.class)
                .hasMessageContaining("回答正文不能为空");
    }

    @Test
    void mapsMalformedJsonToInvalidStructuredOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        // 模型返回说明文字而不是约定 JSON，用于证明解析失败有独立稳定类别
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("无法按要求输出"));
        OpenAiCompatibleConversationAnswerClient client =
                new OpenAiCompatibleConversationAnswerClient(chatModel);

        // 不向业务层泄漏框架解析异常或模型原始响应
        assertThatThrownBy(() -> client.answer(request()))
                .isInstanceOf(ConversationAnswerInvalidOutputException.class)
                .hasMessageContaining("结构无法解析");
    }

    @Test
    void preservesModelFailureForServiceLevelClassification() {
        ChatModel chatModel = mock(ChatModel.class);
        IllegalStateException modelFailure = new IllegalStateException("测试模型不可用");
        // 只有结构解析异常属于非法输出，模型连接类异常必须保留给业务层分类
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(modelFailure);
        OpenAiCompatibleConversationAnswerClient client =
                new OpenAiCompatibleConversationAnswerClient(chatModel);

        assertThatThrownBy(() -> client.answer(request()))
                .isSameAs(modelFailure)
                .isNotInstanceOf(ConversationAnswerInvalidOutputException.class);
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
     * 构建带单个服务端限定分片的虚构问答请求。
     *
     * @return 问答请求
     */
    private ConversationAnswerRequest request() {
        return new ConversationAnswerRequest(
                "年会场地在哪里？",
                List.of(new DocumentChunk(
                        "chunk-1",
                        "section-1",
                        "场地安排",
                        1,
                        "活动场地位于滨海厅。",
                        0,
                        11
                ))
        );
    }
}
