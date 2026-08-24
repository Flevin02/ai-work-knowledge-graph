package com.flevin.knowgraph.server.service.association;

import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 文档关联领域模型到接口响应的映射器。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentAssociationResponseMapper {

    /**
     * 将文档关联运行和已组装的关系响应转换为运行响应。
     *
     * @param run 文档关联运行
     * @param relations 关系响应
     * @return 文档关联运行响应
     */
    @Mapping(target = "runId", source = "run.id")
    @Mapping(target = "relations", source = "relations")
    DocumentAssociationRunResponse toRunResponse(
            DocumentAssociationRun run,
            List<DocumentRelationResponse> relations
    );

    /**
     * 将关系证据转换为接口嵌套响应。
     *
     * @param evidence 文档关系证据
     * @return 关系证据响应
     */
    DocumentRelationResponse.Evidence toEvidenceResponse(DocumentRelationEvidence evidence);

    /**
     * 将文档关系和证据转换为接口响应。
     *
     * @param relation 文档关系
     * @param evidences 关系证据响应
     * @return 文档关系响应
     */
    @Mapping(target = "evidences", source = "evidences")
    DocumentRelationResponse toRelationResponse(
            DocumentRelation relation,
            List<DocumentRelationResponse.Evidence> evidences
    );
}
