package com.flevin.knowgraph.server.service.space;

import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 知识空间领域模型到接口响应的映射器。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface KnowledgeSpaceResponseMapper {

    /**
     * 将知识空间领域模型转换为接口响应。
     *
     * @param space 知识空间领域模型
     * @return 知识空间响应
     */
    KnowledgeSpaceResponse toResponse(KnowledgeSpace space);
}
