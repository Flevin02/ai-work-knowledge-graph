package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrdMarkdownSectionParserTests {

    private final PrdMarkdownSectionParser parser = new PrdMarkdownSectionParser();

    @Test
    void parsesPreambleHierarchyAndIgnoresCodeFenceHeadings() {
        String content = """
                ---
                title: 用户中心 PRD
                ---
                # 用户中心
                项目背景。
                ## 登录功能
                用户可以登录。
                ```markdown
                # 代码示例中的标题
                ```
                ### 验收标准
                - 登录成功后进入首页
                """;

        // 使用确定性 Markdown 规则解析 PRD 章节
        List<DocumentSection> sections = parser.parse(content);

        assertThat(sections)
                .extracting(DocumentSection::title)
                .containsExactly("文档前言", "用户中心", "登录功能", "验收标准");
        assertThat(sections.get(2).sectionPath()).isEqualTo("用户中心 > 登录功能");
        assertThat(sections.get(3).sectionPath()).isEqualTo("用户中心 > 登录功能 > 验收标准");

        // 逐章节验证原文偏移能够准确反向定位
        sections.forEach(section -> assertThat(content.substring(
                section.startOffset(),
                section.endOffset()
        )).isEqualTo(section.contentText()));
    }

    @Test
    void treatsDocumentWithoutHeadingAsSingleRootSection() {
        String content = "产品目标和范围说明。";

        // 解析没有 Markdown 标题的纯文本内容
        List<DocumentSection> sections = parser.parse(content);

        assertThat(sections).hasSize(1);
        assertThat(sections.getFirst().title()).isEqualTo("文档前言");
        assertThat(sections.getFirst().contentText()).isEqualTo(content);
        assertThat(sections.getFirst().startOffset()).isZero();
        assertThat(sections.getFirst().endOffset()).isEqualTo(content.length());
    }
}
