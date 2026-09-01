package com.flevin.knowgraph.server.service.ai.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 倒数排序融合工具单元测试，只验证纯函数排序语义。
 */
class ReciprocalRankFusionTests {

    @Test
    void fusesTwoRankingsUsingRankOnlyContribution() {
        // 内容候选排名 a > b > c，语义候选排名 c > d
        List<ReciprocalRankFusion.FusedCandidate<Long>> fused = ReciprocalRankFusion.fuse(
                List.of(1L, 2L, 3L),
                List.of(3L, 4L),
                ReciprocalRankFusion.RRF_CONSTANT,
                8
        );

        // a 和 c 都被两路命中，c 因语义排名第一获得更大贡献
        assertThat(fused).extracting(ReciprocalRankFusion.FusedCandidate<Long>::documentId)
                .containsExactly(3L, 1L, 2L, 4L);
        assertThat(fused).allSatisfy(candidate ->
                assertThat(candidate.score()).isPositive());
    }

    @Test
    void keepsDeterministicOrderForEqualScoresAndTruncatesToTopK() {
        // 两路完全不相交时，所有候选各只有一路贡献；同分候选按文档标识升序
        List<ReciprocalRankFusion.FusedCandidate<Long>> fused = ReciprocalRankFusion.fuse(
                List.of(30L, 10L, 20L),
                List.of(),
                ReciprocalRankFusion.RRF_CONSTANT,
                2
        );

        // 30、10、20 各得 1/(60+rank)，同 rank 无同分；截取前两名后 30 分最高
        assertThat(fused).extracting(ReciprocalRankFusion.FusedCandidate<Long>::documentId)
                .containsExactly(30L, 10L);
    }

    @Test
    void rejectsInvalidConstantTopKOrDuplicateCandidates() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(
                List.of(1L), List.of(), 0, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RRF 常数");
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(
                List.of(1L), List.of(), 60, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TopK");
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(
                List.of(1L, 1L), List.of(), 60, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        // Arrays.asList 允许 null 元素，用于验证工具自身拒绝空标识
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(
                List.of(1L), java.util.Arrays.asList(1L, null), 60, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空候选标识");
    }
}
