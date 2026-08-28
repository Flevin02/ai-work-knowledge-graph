package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.config.properties.RagProperties;
import org.springframework.stereotype.Component;

/**
 * 统一生成章节解析和分片事实版本，防止不同参数产生的分片被静默混用。
 */
@Component
public class DocumentRagVersionResolver {

    private final RagProperties properties;

    public DocumentRagVersionResolver(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取当前章节解析规则版本。
     *
     * @return 非空解析规则版本
     */
    public String parserVersion() {
        // 规范化配置文本并拒绝空版本，避免无法重建的匿名章节事实
        return requireVersion(properties.getSectionParserVersion(), "章节解析规则版本不能为空");
    }

    /**
     * 获取包含解析规则、分片算法和窗口参数的完整分片版本。
     *
     * @return 可直接写入分片和向量事实的版本字符串
     */
    public String chunkVersion() {
        // 读取当前解析规则版本，确保解析算法变化形成新的分片边界
        String parserVersion = parserVersion();
        // 规范化分片策略版本，拒绝无法追溯的空版本
        String chunkStrategyVersion = requireVersion(
                properties.getChunkStrategyVersion(),
                "分片策略版本不能为空"
        );
        if (properties.getMaxChunkChars() <= 0) {
            throw new IllegalStateException("分片最大字符数必须大于零");
        }
        if (properties.getOverlapChars() < 0
                || properties.getOverlapChars() >= properties.getMaxChunkChars()) {
            throw new IllegalStateException("分片重叠字符数必须大于等于零且小于最大字符数");
        }

        return "%s+%s:max-%d:overlap-%d".formatted(
                parserVersion,
                chunkStrategyVersion,
                properties.getMaxChunkChars(),
                properties.getOverlapChars()
        );
    }

    /**
     * 清理并校验版本配置。
     *
     * @param value 原始版本配置
     * @param errorMessage 空值错误说明
     * @return 清理后的版本
     */
    private String requireVersion(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(errorMessage);
        }

        // 移除配置两侧无意义空白，确保数据库版本值稳定
        return value.strip();
    }
}
