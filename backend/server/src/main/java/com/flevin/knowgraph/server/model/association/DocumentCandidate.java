package com.flevin.knowgraph.server.model.association;

import com.flevin.knowgraph.server.model.document.SourceDocumentType;

import java.util.List;

/**
 * 无 Embedding 文档候选召回结果。
 *
 * @param documentId 候选来源资料标识
 * @param name 候选资料原始文件名
 * @param kind 候选资料文件格式
 * @param documentType 候选资料业务类型
 * @param contentHash 候选资料内容指纹
 * @param summary 候选资料最近一次成功抽取生成的自然摘要；没有成功摘要时回退导入预览
 * @param title 候选资料确定性标题
 * @param matchedChannels 命中的召回通道，按固定优先级排列
 * @param matchedTerms 命中的可解释关键词，按稳定顺序排列
 * @param confirmedTags 命中的已确认标签名称，仅在用户显式开启标签通道时返回
 * @param score 仅用于稳定排序和技术详情展示的规则分数
 * @param rank 在本次召回结果中的 1-based 排名
 */
public record DocumentCandidate(
        String documentId,
        String name,
        String kind,
        SourceDocumentType documentType,
        String contentHash,
        String summary,
        String title,
        List<String> matchedChannels,
        List<String> matchedTerms,
        List<String> confirmedTags,
        int score,
        int rank
) {

    public DocumentCandidate {
        matchedChannels = List.copyOf(matchedChannels);
        matchedTerms = List.copyOf(matchedTerms);
        confirmedTags = List.copyOf(confirmedTags);
    }
}
