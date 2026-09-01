package com.flevin.knowgraph.server.service.ai.embedding;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 倒数排序融合（RRF）工具，用于内容候选和语义候选的实验对照。
 *
 * <p>RRF 只使用排名计算融合分数：score = Σ 1/(constant + rank)，排名从 1 开始。
 * 该方法对两路分数的量纲不敏感，适合规则分数和余弦相似度这类不可直接比较的列表；
 * 融合结果只用于候选排序实验，不构成关系事实。候选标识类型由调用方决定，
 * 既可以是数据库来源资料标识，也可以是实验中的固定 fixture 标识。</p>
 */
public final class ReciprocalRankFusion {

    /** 阶段 3 冻结的 RRF 常数，经典文献默认值。 */
    public static final int RRF_CONSTANT = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * 融合两路按排名排列的候选标识列表。
     *
     * @param <T> 候选标识类型
     * @param firstRanking 第一路候选，按排名升序排列
     * @param secondRanking 第二路候选，按排名升序排列
     * @param constant RRF 常数，必须大于零
     * @param topK 融合后保留的候选数量上限
     * @return 按融合分数降序、标识自然序升序稳定排列的融合候选
     */
    public static <T extends Comparable<T>> List<FusedCandidate<T>> fuse(
            List<T> firstRanking,
            List<T> secondRanking,
            int constant,
            int topK
    ) {
        if (firstRanking == null || secondRanking == null) {
            throw new IllegalArgumentException("参与融合的候选列表不能为空");
        }
        if (constant <= 0) {
            throw new IllegalArgumentException("RRF 常数必须大于零");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("融合 TopK 必须大于零");
        }

        Map<T, Double> scores = new LinkedHashMap<>();
        accumulate(scores, firstRanking, constant, "第一路候选");
        accumulate(scores, secondRanking, constant, "第二路候选");

        return scores.entrySet().stream()
                .sorted(Map.Entry.<T, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(topK)
                .map(entry -> new FusedCandidate<>(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 把一路候选的 RRF 贡献累加进分数映射。
     *
     * @param <T> 候选标识类型
     * @param scores 候选标识到当前累计分数的映射
     * @param ranking 按排名升序排列的候选
     * @param constant RRF 常数
     * @param rankingLabel 列表名称，用于重复候选的定位
     */
    private static <T> void accumulate(
            Map<T, Double> scores,
            List<T> ranking,
            int constant,
            String rankingLabel
    ) {
        Set<T> seen = new LinkedHashSet<>(ranking.size());
        for (int index = 0; index < ranking.size(); index++) {
            T candidateId = ranking.get(index);
            if (candidateId == null) {
                throw new IllegalArgumentException(rankingLabel + "包含空候选标识");
            }
            if (!seen.add(candidateId)) {
                throw new IllegalArgumentException(rankingLabel + "包含重复候选标识: " + candidateId);
            }

            // RRF 贡献只依赖 1-based 排名，不依赖原始分数
            scores.merge(candidateId, 1.0D / (constant + index + 1), Double::sum);
        }
    }

    /**
     * 融合后的一条候选。
     *
     * @param <T> 候选标识类型
     * @param documentId 候选标识
     * @param score 两路 RRF 贡献之和
     */
    public record FusedCandidate<T>(T documentId, double score) {
    }
}
