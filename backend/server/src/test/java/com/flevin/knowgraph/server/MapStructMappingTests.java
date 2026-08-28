package com.flevin.knowgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.repository.entity.DocumentAssociationRunEntity;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkEntity;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkIndexStateEntity;
import com.flevin.knowgraph.server.repository.entity.DocumentSectionEntity;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import com.flevin.knowgraph.server.repository.entity.SourceDocumentEntity;
import com.flevin.knowgraph.server.repository.mapping.DocumentAssociationRunEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentChunkEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentChunkIndexStateEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentSectionEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.GraphEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.PersistenceMappingSupport;
import com.flevin.knowgraph.server.repository.mapping.SourceDocumentEntityMapper;
import com.flevin.knowgraph.server.service.association.DocumentAssociationResponseMapper;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MapStruct 持久化与接口响应映射的集中边界测试。
 */
class MapStructMappingTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-24T01:02:03Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-24T02:03:04Z");

    private final SourceDocumentEntityMapper sourceDocumentEntityMapper;
    private final GraphEntityMapper graphEntityMapper;
    private final DocumentAssociationRunEntityMapper associationRunEntityMapper;
    private final DocumentSectionEntityMapper documentSectionEntityMapper;
    private final DocumentChunkEntityMapper documentChunkEntityMapper;
    private final DocumentChunkIndexStateEntityMapper documentChunkIndexStateEntityMapper;
    private final DocumentAssociationResponseMapper associationResponseMapper;

    MapStructMappingTests() {
        // 创建共享基础类型转换器，为生成映射器提供时间、枚举和 JSON 转换能力
        PersistenceMappingSupport mappingSupport = new PersistenceMappingSupport(new ObjectMapper());

        // 获取 MapStruct 生成的来源资料映射器
        SourceDocumentEntityMapper sourceDocumentMapper = Mappers.getMapper(SourceDocumentEntityMapper.class);
        // 为来源资料生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(sourceDocumentMapper, "persistenceMappingSupport", mappingSupport);
        this.sourceDocumentEntityMapper = sourceDocumentMapper;

        // 获取 MapStruct 生成的图谱映射器
        GraphEntityMapper graphMapper = Mappers.getMapper(GraphEntityMapper.class);
        // 为图谱生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(graphMapper, "persistenceMappingSupport", mappingSupport);
        this.graphEntityMapper = graphMapper;

        // 获取 MapStruct 生成的文档关联运行映射器
        DocumentAssociationRunEntityMapper runMapper = Mappers.getMapper(DocumentAssociationRunEntityMapper.class);
        // 为文档关联运行生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(runMapper, "persistenceMappingSupport", mappingSupport);
        this.associationRunEntityMapper = runMapper;

        // 获取章节事实生成映射器
        DocumentSectionEntityMapper sectionMapper = Mappers.getMapper(DocumentSectionEntityMapper.class);
        // 为章节事实生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(sectionMapper, "persistenceMappingSupport", mappingSupport);
        this.documentSectionEntityMapper = sectionMapper;

        // 获取分片事实生成映射器
        DocumentChunkEntityMapper chunkMapper = Mappers.getMapper(DocumentChunkEntityMapper.class);
        // 为分片事实生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(chunkMapper, "persistenceMappingSupport", mappingSupport);
        this.documentChunkEntityMapper = chunkMapper;

        // 获取分片向量事实生成映射器
        DocumentChunkIndexStateEntityMapper indexStateMapper =
                Mappers.getMapper(DocumentChunkIndexStateEntityMapper.class);
        // 为向量事实生成映射器注入共享基础类型转换器
        ReflectionTestUtils.setField(indexStateMapper, "persistenceMappingSupport", mappingSupport);
        this.documentChunkIndexStateEntityMapper = indexStateMapper;

        // 响应映射器没有外部转换依赖，可直接获取生成实现
        this.associationResponseMapper = Mappers.getMapper(DocumentAssociationResponseMapper.class);
    }

    @Test
    void mapsSourceDocumentEnumTimeAndNullableFileSizeInBothDirections() {
        SourceDocumentEntity entity = new SourceDocumentEntity();
        // 设置数据库文档类型，验证领域枚举解析
        entity.setDocumentType("prd");
        // 模拟历史记录的空文件大小，验证原始类型零值兼容
        entity.setFileSize(null);
        // 设置来源资料首次导入时间
        entity.setImportedAt(CREATED_AT.toString());
        // 设置来源资料最近更新时间
        entity.setUpdatedAt(UPDATED_AT.toString());

        // 将持久化实体转换为来源资料领域模型
        SourceDocument document = sourceDocumentEntityMapper.toDomain(entity);

        // 验证文档类型、空文件大小和时间均按约定转换
        assertThat(document.documentType()).isEqualTo(SourceDocumentType.PRD);
        assertThat(document.fileSize()).isZero();
        assertThat(document.importedAt()).isEqualTo(CREATED_AT);
        assertThat(document.updatedAt()).isEqualTo(UPDATED_AT);

        // 将领域模型转换回持久化实体，验证反向转换规则
        SourceDocumentEntity roundTripEntity = sourceDocumentEntityMapper.toEntity(document);

        // 验证枚举、原始类型和时间按数据库格式写回
        assertThat(roundTripEntity.getDocumentType()).isEqualTo("prd");
        assertThat(roundTripEntity.getFileSize()).isZero();
        assertThat(roundTripEntity.getImportedAt()).isEqualTo(CREATED_AT.toString());
        assertThat(roundTripEntity.getUpdatedAt()).isEqualTo(UPDATED_AT.toString());
    }

    @Test
    void mapsGraphNodeSourceIdsInBothDirectionsAndRejectsInvalidJson() {
        GraphNodeEntity entity = new GraphNodeEntity();
        // 设置图谱节点类型，验证字段改名映射
        entity.setNodeType("requirement");
        // 设置来源资料标识 JSON，验证列表解析
        entity.setSourceIdsJson("[101,102]");
        // 设置图谱节点创建时间
        entity.setCreatedAt(CREATED_AT.toString());
        // 设置图谱节点更新时间
        entity.setUpdatedAt(UPDATED_AT.toString());

        // 将图谱节点实体转换为领域模型
        GraphNode node = graphEntityMapper.toDomain(entity);

        // 验证节点类型、来源列表和时间转换结果
        assertThat(node.type()).isEqualTo("requirement");
        assertThat(node.sourceIds()).containsExactly(101L, 102L);
        assertThat(node.createdAt()).isEqualTo(CREATED_AT);
        assertThat(node.updatedAt()).isEqualTo(UPDATED_AT);

        // 将领域节点转换回持久化实体，验证 JSON 序列化
        GraphNodeEntity roundTripEntity = graphEntityMapper.toEntity(node);

        // 验证改名字段和来源资料 JSON 按统一格式写回
        assertThat(roundTripEntity.getNodeType()).isEqualTo("requirement");
        assertThat(roundTripEntity.getSourceIdsJson()).isEqualTo("[101,102]");

        GraphNodeEntity invalidEntity = new GraphNodeEntity();
        // 设置非法来源资料 JSON，验证持久化数据损坏时明确失败
        invalidEntity.setSourceIdsJson("{invalid-json}");

        // 转换非法 JSON 时必须保留可定位的稳定异常信息
        assertThatThrownBy(() -> graphEntityMapper.toDomain(invalidEntity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图谱节点来源资料标识不是有效 JSON");
    }

    @Test
    void mapsNullableAssociationRunStatisticsAndCompletedTime() {
        DocumentAssociationRunEntity entity = new DocumentAssociationRunEntity();
        // 设置文档关联运行标识
        entity.setId(TestIdFixtures.id("run-1"));
        // 设置文档关联主体资料标识
        entity.setSourceDocumentId(TestIdFixtures.id("document-a"));
        // 设置文档关联运行状态
        entity.setStatus("completed");
        // 设置运行创建时间，完成时间保持为空
        entity.setCreatedAt(CREATED_AT.toString());

        // 将可空统计字段的持久化实体转换为领域运行模型
        DocumentAssociationRun run = associationRunEntityMapper.toDomain(entity);

        // 验证历史空统计值按零处理且空完成时间保持为空
        assertThat(run.candidateCount()).isZero();
        assertThat(run.comparedCount()).isZero();
        assertThat(run.suggestionCount()).isZero();
        assertThat(run.tagCandidateCount()).isZero();
        assertThat(run.keywordCandidateCount()).isZero();
        assertThat(run.semanticCandidateCount()).isZero();
        assertThat(run.modelRequestCount()).isZero();
        assertThat(run.retryCount()).isZero();
        assertThat(run.createdAt()).isEqualTo(CREATED_AT);
        assertThat(run.completedAt()).isNull();

        // 将运行领域模型和关系集合转换为接口响应
        DocumentAssociationRunResponse response = associationResponseMapper.toRunResponse(run, List.of());

        // 验证领域标识按接口契约改名为 runId
        assertThat(response.runId()).isEqualTo(TestIdFixtures.id("run-1"));
        assertThat(response.relations()).isEmpty();
    }

    @Test
    void mapsDocumentRelationEvidenceToSafeNestedResponse() {
        DocumentRelationEvidence evidence = new DocumentRelationEvidence(
                TestIdFixtures.id("evidence-1"),
                TestIdFixtures.id("space-1"),
                TestIdFixtures.id("relation-1"),
                TestIdFixtures.id("document-a"),
                "chunk-1",
                "需求 > 验收标准",
                "必须支持原文证据反查",
                12,
                24,
                "source",
                CREATED_AT
        );

        // 将包含内部关联字段的证据领域模型转换为安全嵌套响应
        DocumentRelationResponse.Evidence response = associationResponseMapper.toEvidenceResponse(evidence);

        // 验证接口所需的可定位证据字段完整保留
        assertThat(response.id()).isEqualTo(TestIdFixtures.id("evidence-1"));
        assertThat(response.sourceDocumentId()).isEqualTo(TestIdFixtures.id("document-a"));
        assertThat(response.chunkId()).isEqualTo("chunk-1");
        assertThat(response.sectionPath()).isEqualTo("需求 > 验收标准");
        assertThat(response.quote()).isEqualTo("必须支持原文证据反查");
        assertThat(response.startOffset()).isEqualTo(12);
        assertThat(response.endOffset()).isEqualTo(24);
        assertThat(response.evidenceRole()).isEqualTo("source");
    }

    @Test
    void mapsDocumentSectionFactInBothDirections() {
        DocumentSectionEntity entity = new DocumentSectionEntity();
        // 设置章节事实的空间、资料、版本和定位字段
        entity.setId(101L);
        entity.setSpaceId(11L);
        entity.setSourceDocumentId(21L);
        entity.setSectionId("section-1");
        entity.setParserVersion("parser-v1");
        entity.setTitle("需求");
        entity.setLevel(2);
        entity.setSectionPath("产品 > 需求");
        entity.setOrdinal(3);
        entity.setContentText("必须支持原文证据反查");
        entity.setStartOffset(12);
        entity.setEndOffset(24);
        entity.setContentHash("hash-section");
        entity.setCreatedAt(CREATED_AT.toString());
        entity.setUpdatedAt(UPDATED_AT.toString());

        // 将章节持久化实体转换为领域事实
        DocumentSectionFact fact = documentSectionEntityMapper.toDomain(entity);

        // 验证章节定位、版本和时间字段完整保留
        assertThat(fact.id()).isEqualTo(101L);
        assertThat(fact.sectionPath()).isEqualTo("产品 > 需求");
        assertThat(fact.ordinal()).isEqualTo(3);
        assertThat(fact.startOffset()).isEqualTo(12);
        assertThat(fact.endOffset()).isEqualTo(24);
        assertThat(fact.createdAt()).isEqualTo(CREATED_AT);
        assertThat(fact.updatedAt()).isEqualTo(UPDATED_AT);

        // 将章节领域事实转换回持久化实体，验证时间格式和字段映射对称
        DocumentSectionEntity roundTripEntity = documentSectionEntityMapper.toEntity(fact);
        assertThat(roundTripEntity.getSectionId()).isEqualTo("section-1");
        assertThat(roundTripEntity.getParserVersion()).isEqualTo("parser-v1");
        assertThat(roundTripEntity.getStartOffset()).isEqualTo(12);
        assertThat(roundTripEntity.getEndOffset()).isEqualTo(24);
        assertThat(roundTripEntity.getCreatedAt()).isEqualTo(CREATED_AT.toString());
        assertThat(roundTripEntity.getUpdatedAt()).isEqualTo(UPDATED_AT.toString());
    }

    @Test
    void mapsDocumentChunkFactInBothDirections() {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        // 设置分片所属章节、全局顺序和版本边界
        entity.setId(102L);
        entity.setSpaceId(11L);
        entity.setSourceDocumentId(21L);
        entity.setSectionRecordId(101L);
        entity.setSectionId("section-1");
        entity.setChunkId("chunk-1");
        entity.setParserVersion("parser-v1");
        entity.setSectionPath("产品 > 需求");
        entity.setOrdinal(1);
        entity.setDocumentOrdinal(4);
        entity.setContentText("必须支持原文证据反查");
        entity.setStartOffset(12);
        entity.setEndOffset(24);
        entity.setContentHash("hash-chunk");
        entity.setChunkVersion("chunk-v1");
        entity.setCreatedAt(CREATED_AT.toString());
        entity.setUpdatedAt(UPDATED_AT.toString());

        // 将分片持久化实体转换为领域事实
        DocumentChunkFact fact = documentChunkEntityMapper.toDomain(entity);

        // 验证章节关联、文档内全局顺序和原文偏移完整保留
        assertThat(fact.sectionRecordId()).isEqualTo(101L);
        assertThat(fact.chunkId()).isEqualTo("chunk-1");
        assertThat(fact.documentOrdinal()).isEqualTo(4);
        assertThat(fact.chunkVersion()).isEqualTo("chunk-v1");
        assertThat(fact.startOffset()).isEqualTo(12);
        assertThat(fact.endOffset()).isEqualTo(24);

        // 将分片领域事实转换回持久化实体，验证版本和时间格式不漂移
        DocumentChunkEntity roundTripEntity = documentChunkEntityMapper.toEntity(fact);
        assertThat(roundTripEntity.getSectionRecordId()).isEqualTo(101L);
        assertThat(roundTripEntity.getDocumentOrdinal()).isEqualTo(4);
        assertThat(roundTripEntity.getChunkVersion()).isEqualTo("chunk-v1");
        assertThat(roundTripEntity.getCreatedAt()).isEqualTo(CREATED_AT.toString());
        assertThat(roundTripEntity.getUpdatedAt()).isEqualTo(UPDATED_AT.toString());
    }

    @Test
    void mapsDocumentChunkIndexStateVectorJsonAndMetadataInBothDirections() {
        DocumentChunkIndexStateEntity entity = new DocumentChunkIndexStateEntity();
        // 设置可重建向量事实的完整模型、分片和状态元数据
        entity.setId(103L);
        entity.setSpaceId(11L);
        entity.setSourceDocumentId(21L);
        entity.setChunkRecordId(102L);
        entity.setChunkId("chunk-1");
        entity.setContentHash("hash-chunk");
        entity.setChunkVersion("chunk-v1");
        entity.setEmbeddingProvider("fake");
        entity.setEmbeddingModel("deterministic-char-hash");
        entity.setEmbeddingVersion("fake-embedding-v1");
        entity.setDimension(2);
        entity.setVectorJson("[0.25,-0.5]");
        entity.setStatus("ready");
        entity.setErrorMessage(null);
        entity.setCreatedAt(CREATED_AT.toString());
        entity.setUpdatedAt(UPDATED_AT.toString());

        // 将向量索引状态实体转换为领域事实并重新执行向量边界校验
        DocumentChunkIndexStateFact fact = documentChunkIndexStateEntityMapper.toDomain(entity);

        // 验证模型描述字段、分片版本、状态和向量值完整保留
        assertThat(fact.chunkVersion()).isEqualTo("chunk-v1");
        assertThat(fact.embeddingProvider()).isEqualTo("fake");
        assertThat(fact.embeddingModel()).isEqualTo("deterministic-char-hash");
        assertThat(fact.embeddingVersion()).isEqualTo("fake-embedding-v1");
        assertThat(fact.status()).isEqualTo("ready");
        assertThat(fact.vector().values()).containsExactly(0.25F, -0.5F);

        // 将领域向量事实转换回持久化实体，验证 JSON 和时间字段可重建
        DocumentChunkIndexStateEntity roundTripEntity = documentChunkIndexStateEntityMapper.toEntity(fact);
        assertThat(roundTripEntity.getChunkVersion()).isEqualTo("chunk-v1");
        assertThat(roundTripEntity.getDimension()).isEqualTo(2);
        assertThat(roundTripEntity.getVectorJson()).isEqualTo("[0.25,-0.5]");
        assertThat(roundTripEntity.getStatus()).isEqualTo("ready");
        assertThat(roundTripEntity.getCreatedAt()).isEqualTo(CREATED_AT.toString());
        assertThat(roundTripEntity.getUpdatedAt()).isEqualTo(UPDATED_AT.toString());
    }

}
