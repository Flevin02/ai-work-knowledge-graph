package com.flevin.knowgraph.server.model.ai.rag;

/**
 * 从来源文档中确定性解析出的章节结构。
 *
 * @param sectionId 当前文档内稳定的章节标识
 * @param title 章节标题
 * @param level Markdown 标题层级；无标题文档使用零
 * @param sectionPath 从顶层标题到当前标题的路径
 * @param ordinal 当前文档内章节顺序
 * @param contentText 包含标题行和直属正文的原文
 * @param startOffset 章节在原文中的起始偏移
 * @param endOffset 章节在原文中的结束偏移，不包含该位置字符
 */
public record DocumentSection(
        String sectionId,
        String title,
        int level,
        String sectionPath,
        int ordinal,
        String contentText,
        int startOffset,
        int endOffset
) {
}
