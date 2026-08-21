package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiDocumentSummaryRequest;
import com.flevin.knowgraph.server.service.ai.openai.OpenAiCompatibleAiExtractionClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI-compatible 流式适配器单元测试，不连接真实模型端点。
 */
class OpenAiCompatibleAiExtractionClientTests {

    @Test
    void forwardsRealDeltasAndValidatesOnlyTheCompleteStructuredResponse() {
        String entitySummary = "登录功能支持手机号验证码登录。".repeat(8);
        String responseJson = """
                {
                  "summary": "登录功能支持手机号验证码。",
                  "entities": [
                    {
                      "candidateId": "entity-1",
                      "type": "FEATURE",
                      "name": "登录功能",
                      "summary": "%s",
                      "evidenceIds": ["evidence-1"]
                    }
                  ],
                  "relations": [],
                  "evidences": [
                    {
                      "evidenceId": "evidence-1",
                      "sourceDocumentId": "document-1",
                      "chunkId": "chunk-1",
                      "sectionPath": "用户中心 / 登录功能",
                      "quote": "登录功能支持手机号验证码。"
                    }
                  ],
                  "conflicts": []
                }
                """.formatted(entitySummary);
        String firstDelta = responseJson.substring(0, responseJson.length() / 2);
        String secondDelta = responseJson.substring(responseJson.length() / 2);
        ChatModel chatModel = new ChatModel() {
        };
        StreamingChatModel streamingChatModel = new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest chatRequest,
                    StreamingChatResponseHandler handler
            ) {
                // 按供应商回调顺序发送两段原始文本
                handler.onPartialResponse(firstDelta);
                handler.onPartialResponse(secondDelta);

                // 完整响应到达后触发 DTO 解析和证据校验
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(responseJson))
                        .build());
            }
        };

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );
            OpenAiCompatibleAiExtractionClient client = new OpenAiCompatibleAiExtractionClient(
                    chatModel,
                    streamingChatModel,
                    validator,
                    "prompt-test",
                    "summary-prompt-test",
                    "schema-test",
                    false
            );
            AiExtractionRequest request = new AiExtractionRequest(
                    "document-1",
                    "登录功能.md",
                    "prd",
                    "chunk-1",
                    "用户中心 / 登录功能",
                    "登录功能支持手机号验证码。"
            );
            List<String> receivedDeltas = new ArrayList<>();

            // 执行 Fake 流式模型并收集供应商实际提供的增量
            AiExtractionResult result = client.extract(request, receivedDeltas::add);

            assertThat(receivedDeltas).containsExactly(firstDelta, secondDelta);
            assertThat(result.summary()).isEqualTo("登录功能支持手机号验证码。");
            assertThat(result.entities()).hasSize(1);
            assertThat(result.evidences()).hasSize(1);
        }
    }

    @Test
    void generatesNaturalDocumentSummaryFromOrderedChunkSummaries() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                // Fake 同步模型只返回一段完整的自然中文摘要
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("用户中心支持手机号验证码登录，资料同时说明了发布前验收要求。"))
                        .build();
            }
        };
        StreamingChatModel streamingChatModel = new StreamingChatModel() {
        };

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );
            OpenAiCompatibleAiExtractionClient client = new OpenAiCompatibleAiExtractionClient(
                    chatModel,
                    streamingChatModel,
                    validator,
                    "prompt-test",
                    "summary-prompt-test",
                    "schema-test",
                    false
            );

            // 只把分片摘要按原文顺序交给全文汇总客户端
            String summary = client.summarize(new AiDocumentSummaryRequest(
                    "document-1",
                    "用户中心.md",
                    "prd",
                    List.of(
                            new AiDocumentSummaryRequest.ChunkSummary(
                                    "chunk-1", "用户中心", "用户中心提供登录能力。"
                            ),
                            new AiDocumentSummaryRequest.ChunkSummary(
                                    "chunk-2", "发布要求", "发布前需要完成验收。"
                            )
                    )
            ));

            assertThat(summary).isEqualTo("用户中心支持手机号验证码登录，资料同时说明了发布前验收要求。");
        }
    }
}
