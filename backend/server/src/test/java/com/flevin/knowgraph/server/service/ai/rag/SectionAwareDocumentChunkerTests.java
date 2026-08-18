package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SectionAwareDocumentChunkerTests {

    @Test
    void keepsShortSectionWholeAndSplitsLongSectionWithOffsets() {
        String content = """
                # 登录功能
                第一段用于说明登录功能背景和业务目标。
                第二段用于说明手机号验证码登录的交互流程。
                第三段用于说明验证码错误和过期时的异常处理。
                第四段用于说明登录成功后的页面跳转和审计记录。
                """;

        DocumentSection section = new DocumentSection(
                "section-1",
                "登录功能",
                1,
                "登录功能",
                1,
                content,
                20,
                20 + content.length()
        );

        SectionAwareDocumentChunker chunker = new SectionAwareDocumentChunker(50, 10);

        // 使用小窗口验证长章节换行切分和重叠偏移
        List<DocumentChunk> chunks = chunker.chunk(List.of(section));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.contentText().length()).isLessThanOrEqualTo(50);
            assertThat(content.substring(
                    chunk.startOffset() - section.startOffset(),
                    chunk.endOffset() - section.startOffset()
            )).isEqualTo(chunk.contentText());
        });
        assertThat(chunks.get(1).startOffset()).isLessThan(chunks.getFirst().endOffset());
    }

    @Test
    void returnsOneChunkForShortSection() {
        String content = "# 范围\n只包含登录和退出。";
        DocumentSection section = new DocumentSection(
                "section-1",
                "范围",
                1,
                "范围",
                1,
                content,
                0,
                content.length()
        );

        SectionAwareDocumentChunker chunker = new SectionAwareDocumentChunker(100, 10);

        // 保留短章节整体，避免过度碎片化
        List<DocumentChunk> chunks = chunker.chunk(List.of(section));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().contentText()).isEqualTo(content);
        assertThat(chunks.getFirst().sectionPath()).isEqualTo("范围");
    }
}
