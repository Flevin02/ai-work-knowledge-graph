package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PRD Markdown 章节解析器，使用确定性标题规则保留层级和原文偏移。
 */
@Component
public class PrdMarkdownSectionParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})[ \\t]+(.+?)\\s*$");
    private static final String PREAMBLE_TITLE = "文档前言";

    /**
     * 将 Markdown 原文解析为按出现顺序排列的章节。
     *
     * @param contentText 完整 Markdown 原文
     * @return 带标题路径和原文偏移的章节列表；空白文本返回空列表
     */
    public List<DocumentSection> parse(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return List.of();
        }

        // 扫描代码围栏之外的 Markdown 标题
        List<Heading> headings = findHeadings(contentText);
        if (headings.isEmpty()) {
            // 将无标题文档整体视为一个可追溯根章节
            return List.of(createSection(
                    1,
                    PREAMBLE_TITLE,
                    0,
                    PREAMBLE_TITLE,
                    contentText,
                    0,
                    contentText.length()
            ));
        }

        List<DocumentSection> sections = new ArrayList<>();
        int ordinal = 1;

        Heading firstHeading = headings.getFirst();
        if (firstHeading.startOffset() > 0
                && !contentText.substring(0, firstHeading.startOffset()).isBlank()) {
            // 保留首个标题前的 Front Matter 或文档说明
            sections.add(createSection(
                    ordinal++,
                    PREAMBLE_TITLE,
                    0,
                    PREAMBLE_TITLE,
                    contentText,
                    0,
                    firstHeading.startOffset()
            ));
        }

        Deque<Heading> headingPath = new ArrayDeque<>();
        for (int headingIndex = 0; headingIndex < headings.size(); headingIndex++) {
            Heading heading = headings.get(headingIndex);

            while (!headingPath.isEmpty() && headingPath.getLast().level() >= heading.level()) {
                // 移除同级或更深标题，恢复当前标题的父级路径
                headingPath.removeLast();
            }

            // 将当前标题加入章节层级路径
            headingPath.addLast(heading);

            // 拼接完整标题路径，供检索元数据过滤和证据展示
            String sectionPath = headingPath.stream()
                    .map(Heading::title)
                    .reduce((parent, child) -> parent + " > " + child)
                    .orElse(heading.title());

            int endOffset = headingIndex + 1 < headings.size()
                    ? headings.get(headingIndex + 1).startOffset()
                    : contentText.length();

            // 按标题到下一个标题的原文边界创建章节
            sections.add(createSection(
                    ordinal++,
                    heading.title(),
                    heading.level(),
                    sectionPath,
                    contentText,
                    heading.startOffset(),
                    endOffset
            ));
        }

        // 返回不可修改快照，防止调用方改变解析顺序
        return List.copyOf(sections);
    }

    /**
     * 扫描代码围栏之外的 Markdown ATX 标题。
     *
     * @param contentText 完整 Markdown 原文
     * @return 标题位置和层级列表
     */
    private List<Heading> findHeadings(String contentText) {
        List<Heading> headings = new ArrayList<>();
        boolean insideCodeFence = false;
        String activeFence = null;
        int lineStart = 0;

        while (lineStart < contentText.length()) {
            // 定位当前行结束位置，同时保留下一行偏移
            int newlineIndex = contentText.indexOf('\n', lineStart);
            int lineEnd = newlineIndex >= 0 ? newlineIndex : contentText.length();

            // 读取当前原文行，不改变其偏移
            String line = contentText.substring(lineStart, lineEnd);
            String trimmedLine = line.stripLeading();

            String fenceMarker = resolveFenceMarker(trimmedLine);
            if (fenceMarker != null) {
                if (!insideCodeFence) {
                    insideCodeFence = true;
                    activeFence = fenceMarker;
                } else if (fenceMarker.equals(activeFence)) {
                    insideCodeFence = false;
                    activeFence = null;
                }
            } else if (!insideCodeFence) {
                // 仅在普通 Markdown 文本中识别标题，忽略代码块内的井号
                Matcher matcher = HEADING_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String title = matcher.group(2).strip();
                    if (!title.isEmpty()) {
                        // 保存标题层级、原文位置和展示文本
                        headings.add(new Heading(
                                matcher.group(1).length(),
                                trimClosingHashes(title),
                                lineStart
                        ));
                    }
                }
            }

            if (newlineIndex < 0) {
                break;
            }
            lineStart = newlineIndex + 1;
        }
        return headings;
    }

    /**
     * 识别 Markdown 反引号或波浪线代码围栏。
     *
     * @param trimmedLine 已移除行首空白的文本
     * @return 围栏标识；普通文本返回空
     */
    private String resolveFenceMarker(String trimmedLine) {
        if (trimmedLine.startsWith("```")) {
            return "```";
        }
        if (trimmedLine.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    /**
     * 移除 Markdown 标题可选的结尾井号装饰。
     *
     * @param title 原始标题文本
     * @return 清理后的标题文本
     */
    private String trimClosingHashes(String title) {
        // 仅移除由空白隔开的结尾井号，保留标题正文中的井号
        return title.replaceFirst("\\s+#+\\s*$", "").strip();
    }

    /**
     * 按原文偏移创建章节模型。
     *
     * @param ordinal 章节顺序
     * @param title 章节标题
     * @param level 标题层级
     * @param sectionPath 章节路径
     * @param contentText 完整来源原文
     * @param startOffset 起始偏移
     * @param endOffset 结束偏移
     * @return 章节模型
     */
    private DocumentSection createSection(
            int ordinal,
            String title,
            int level,
            String sectionPath,
            String contentText,
            int startOffset,
            int endOffset
    ) {
        return new DocumentSection(
                "section-" + ordinal,
                title,
                level,
                sectionPath,
                ordinal,
                contentText.substring(startOffset, endOffset),
                startOffset,
                endOffset
        );
    }

    /**
     * Markdown 标题中间模型。
     *
     * @param level 标题层级
     * @param title 标题文本
     * @param startOffset 标题行原文起始偏移
     */
    private record Heading(
            int level,
            String title,
            int startOffset
    ) {
    }
}
