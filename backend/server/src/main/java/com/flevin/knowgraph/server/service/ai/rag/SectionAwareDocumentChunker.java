package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.config.properties.RagProperties;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节感知分片器，优先保留完整章节，只有长章节才按原文边界切分。
 */
@Component
public class SectionAwareDocumentChunker {

    private final int maxChunkChars;
    private final int overlapChars;

    @Autowired
    public SectionAwareDocumentChunker(RagProperties properties) {
        this(properties.getMaxChunkChars(), properties.getOverlapChars());
    }

    SectionAwareDocumentChunker(
            int maxChunkChars,
            int overlapChars
    ) {
        if (maxChunkChars <= 0) {
            throw new IllegalArgumentException("分片最大字符数必须大于零");
        }
        if (overlapChars < 0 || overlapChars >= maxChunkChars) {
            throw new IllegalArgumentException("分片重叠字符数必须大于等于零且小于最大字符数");
        }
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
    }

    /**
     * 将章节列表转换为保持原文偏移的检索分片。
     *
     * @param sections 按原文顺序排列的章节
     * @return 按章节和分片顺序排列的文本分片
     */
    public List<DocumentChunk> chunk(List<DocumentSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();

        // 逐章节切分，避免不同章节内容被静默拼到同一分片
        sections.forEach(section -> chunks.addAll(chunkSection(section)));

        // 返回不可修改快照，防止调用方改变检索顺序
        return List.copyOf(chunks);
    }

    /**
     * 保留短章节整体，长章节优先在换行位置切分并加入受控重叠。
     *
     * @param section 待切分章节
     * @return 当前章节分片
     */
    private List<DocumentChunk> chunkSection(DocumentSection section) {
        String contentText = section.contentText();
        if (contentText.length() <= maxChunkChars) {
            // 短章节整体保留，减少无意义碎片化
            return List.of(createChunk(
                    section,
                    1,
                    0,
                    contentText.length()
            ));
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int relativeStart = 0;
        int ordinal = 1;

        while (relativeStart < contentText.length()) {
            int desiredEnd = Math.min(relativeStart + maxChunkChars, contentText.length());

            // 在窗口后半段寻找最近换行，尽量不切断列表项或段落
            int relativeEnd = resolveChunkEnd(contentText, relativeStart, desiredEnd);

            // 记录当前分片及其原文绝对偏移
            chunks.add(createChunk(section, ordinal++, relativeStart, relativeEnd));

            if (relativeEnd >= contentText.length()) {
                break;
            }

            // 使用受控重叠保留跨边界上下文，并确保游标一定前进
            relativeStart = Math.max(relativeStart + 1, relativeEnd - overlapChars);
        }
        return chunks;
    }

    /**
     * 在目标窗口内选择优先换行的分片结束位置。
     *
     * @param contentText 当前章节原文
     * @param relativeStart 当前分片起点
     * @param desiredEnd 最大窗口终点
     * @return 实际分片终点
     */
    private int resolveChunkEnd(
            String contentText,
            int relativeStart,
            int desiredEnd
    ) {
        if (desiredEnd >= contentText.length()) {
            return contentText.length();
        }

        // 从最大窗口终点向前寻找可读的换行边界
        int newlineIndex = contentText.lastIndexOf('\n', desiredEnd - 1);
        int minimumReadableEnd = relativeStart + maxChunkChars / 2;
        return newlineIndex >= minimumReadableEnd
                ? newlineIndex + 1
                : desiredEnd;
    }

    /**
     * 从章节相对偏移创建可追溯文本分片。
     *
     * @param section 所属章节
     * @param ordinal 章节内分片顺序
     * @param relativeStart 章节内起始偏移
     * @param relativeEnd 章节内结束偏移
     * @return 文本分片
     */
    private DocumentChunk createChunk(
            DocumentSection section,
            int ordinal,
            int relativeStart,
            int relativeEnd
    ) {
        return new DocumentChunk(
                section.sectionId() + "-chunk-" + ordinal,
                section.sectionId(),
                section.sectionPath(),
                ordinal,
                section.contentText().substring(relativeStart, relativeEnd),
                section.startOffset() + relativeStart,
                section.startOffset() + relativeEnd
        );
    }
}
