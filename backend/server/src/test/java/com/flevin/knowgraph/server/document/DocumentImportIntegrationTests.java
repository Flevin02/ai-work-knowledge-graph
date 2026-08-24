package com.flevin.knowgraph.server.document;

import com.flevin.knowgraph.server.model.document.DocumentImportFileStatus;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.graph.GraphRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/document-import.sqlite",
        "app.upload-dir=target/test-data/document-uploads"
})
@AutoConfigureMockMvc
class DocumentImportIntegrationTests {

    private static final String DEFAULT_SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private GraphRepository graphRepository;

    @Autowired
    private KnowledgeSpaceService knowledgeSpaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearImportedDocuments() {
        // 查询上一次测试保存的原始文件路径
        List<String> storagePaths = jdbcTemplate.queryForList(
                "SELECT storage_path FROM source_documents",
                String.class
        );

        // 删除测试生成的原始文件，避免不同测试用例相互影响
        storagePaths.forEach(this::deleteTestFile);

        // 清理可能引用来源资料的图谱证据和关系
        jdbcTemplate.update("DELETE FROM ai_extraction_runs");
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");

        // 再删除来源资料，满足 SQLite 外键约束
        jdbcTemplate.update("DELETE FROM source_documents");

        // 再删除已失去引用的导入批次
        jdbcTemplate.update("DELETE FROM import_batches");

        // 为依赖固定标识的测试准备测试空间，生产 schema 不提供默认空间
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void serviceImportsDocumentAndDetectsDuplicateContent() {
        MockMultipartFile firstFile = new MockMultipartFile(
                "files",
                "第一次筹备会议纪要.md",
                "text/markdown",
                "# 年会筹备\n确认活动主题和负责人。".getBytes(StandardCharsets.UTF_8)
        );

        // 首次导入 Markdown 来源资料
        DocumentImportResponse firstResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                List.of(firstFile)
        );

        assertThat(firstResponse.importedCount()).isEqualTo(1);
        assertThat(firstResponse.duplicateCount()).isZero();
        assertThat(firstResponse.failedCount()).isZero();
        assertThat(firstResponse.results().getFirst().status()).isEqualTo(DocumentImportFileStatus.IMPORTED);

        // 通过 Repository 查询首次导入的来源资料
        List<SourceDocument> savedDocuments = sourceDocumentRepository.findAll(DEFAULT_SPACE_ID);

        assertThat(savedDocuments).hasSize(1);
        assertThat(savedDocuments.getFirst().contentHash()).hasSize(64);

        // 验证原始事实源文件已经保存到测试上传目录
        assertThat(Path.of(savedDocuments.getFirst().storagePath())).exists();

        MockMultipartFile duplicateFile = new MockMultipartFile(
                "files",
                "同内容不同文件名.txt",
                "text/plain",
                "# 年会筹备\n确认活动主题和负责人。".getBytes(StandardCharsets.UTF_8)
        );

        // 再次导入相同字节内容，验证 SHA-256 重复识别
        DocumentImportResponse duplicateResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                List.of(duplicateFile)
        );

        assertThat(duplicateResponse.importedCount()).isZero();
        assertThat(duplicateResponse.duplicateCount()).isEqualTo(1);
        assertThat(duplicateResponse.results().getFirst().status()).isEqualTo(DocumentImportFileStatus.DUPLICATE);

        // 再次查询 Repository，确认重复内容未新增来源记录
        assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).hasSize(1);
    }

    @Test
    void controllerImportsMultiPagePdfAndReturnsPageBoundariesForPreview() throws Exception {
        // 生成包含两页虚构年会内容的可复制文本 PDF
        byte[] pdfBytes = createTextPdf(
                "Annual party plan belongs to the demo project.",
                "Budget review is scheduled for the second page."
        );
        MockMultipartFile pdfFile = new MockMultipartFile(
                "files",
                "虚构年会方案.pdf",
                "application/pdf",
                pdfBytes
        );

        // 通过 multipart 接口导入文本型 PDF
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .file(pdfFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.importedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].document.kind").value("pdf"));

        // 查询持久化记录，获取原文预览接口所需标识
        SourceDocument savedDocument = sourceDocumentRepository.findAll(DEFAULT_SPACE_ID).getFirst();
        String expectedContent = "===== 第 1 页 =====\n"
                + "Annual party plan belongs to the demo project.\n\n"
                + "===== 第 2 页 =====\n"
                + "Budget review is scheduled for the second page.";

        // 读取服务端 PDF 解析文本，验证页码边界和逐页内容均可反查
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/content",
                        DEFAULT_SPACE_ID,
                        savedDocument.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("pdf"))
                .andExpect(jsonPath("$.data.contentText").value(expectedContent));

        MockMultipartFile duplicateFile = new MockMultipartFile(
                "files",
                "相同字节的年会方案副本.pdf",
                "application/pdf",
                pdfBytes
        );

        // 使用相同原始字节再次导入，验证 PDF 继续沿用 SHA-256 空间内去重
        DocumentImportResponse duplicateResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                List.of(duplicateFile)
        );

        assertThat(duplicateResponse.duplicateCount()).isEqualTo(1);
        assertThat(duplicateResponse.results().getFirst().status()).isEqualTo(DocumentImportFileStatus.DUPLICATE);
        assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).hasSize(1);
    }

    @Test
    void serviceReturnsFileLevelPdfFailuresWithoutBlockingValidFile() throws Exception {
        // 准备同批导入中的一份有效文本型 PDF
        MockMultipartFile validPdf = new MockMultipartFile(
                "files",
                "有效方案.pdf",
                "application/pdf",
                createTextPdf("This fictional plan contains extractable text.")
        );

        // 准备损坏、受密码保护、零页和无文本 PDF，覆盖明确失败分类
        List<MultipartFile> files = List.of(
                validPdf,
                new MockMultipartFile(
                        "files",
                        "损坏资料.pdf",
                        "application/pdf",
                        "not-a-pdf".getBytes(StandardCharsets.UTF_8)
                ),
                new MockMultipartFile(
                        "files",
                        "密码资料.pdf",
                        "application/pdf",
                        createEncryptedPdf()
                ),
                new MockMultipartFile(
                        "files",
                        "零页资料.pdf",
                        "application/pdf",
                        createZeroPagePdf()
                ),
                new MockMultipartFile(
                        "files",
                        "扫描资料.pdf",
                        "application/pdf",
                        createTextPdf((String) null)
                )
        );

        // 执行混合批次导入，验证单文件失败不阻断有效 PDF
        DocumentImportResponse response = documentService.importDocuments(DEFAULT_SPACE_ID, files);

        assertThat(response.status().getValue()).isEqualTo("partial_failed");
        assertThat(response.importedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(4);
        assertThat(response.results())
                .extracting(result -> result.message())
                .containsExactly(
                        "来源资料已导入",
                        "PDF 文件已损坏或格式无效，无法解析",
                        "PDF 已加密或受密码保护，当前无法导入",
                        "PDF 不包含任何页面，未创建来源资料",
                        "PDF 不含可提取文本，可能是扫描件；当前未启用 OCR"
                );
        assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).hasSize(1);
    }

    @Test
    void serviceKeepsPdfDuplicateDetectionIsolatedByKnowledgeSpace() throws Exception {
        CreateKnowledgeSpaceRequest request = new CreateKnowledgeSpaceRequest(
                "PDF 空间隔离-" + UUID.randomUUID(),
                "仅使用虚构 PDF 验证空间内去重边界。"
        );

        // 创建第二个知识空间，验证相同 PDF 可在不同空间独立保存
        KnowledgeSpaceResponse otherSpace = knowledgeSpaceService.createSpace(request);
        byte[] pdfBytes = createTextPdf("Shared bytes remain isolated by knowledge space.");

        try {
            // 将相同原始字节分别导入默认空间和新空间
            DocumentImportResponse defaultResponse = documentService.importDocuments(
                    DEFAULT_SPACE_ID,
                    List.of(new MockMultipartFile(
                            "files",
                            "默认空间.pdf",
                            "application/pdf",
                            pdfBytes
                    ))
            );
            DocumentImportResponse otherResponse = documentService.importDocuments(
                    otherSpace.id(),
                    List.of(new MockMultipartFile(
                            "files",
                            "其他空间.pdf",
                            "application/pdf",
                            pdfBytes
                    ))
            );

            assertThat(defaultResponse.importedCount()).isEqualTo(1);
            assertThat(otherResponse.importedCount()).isEqualTo(1);
            assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).hasSize(1);
            assertThat(sourceDocumentRepository.findAll(otherSpace.id())).hasSize(1);
        } finally {
            // 软删除测试空间，避免其继续出现在后续用例的有效空间列表中
            knowledgeSpaceService.deleteSpace(otherSpace.id());
        }
    }

    @Test
    void controllerImportsAndListsPersistedTextDocument() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "files",
                "人员分工.txt",
                "text/plain",
                "行政部负责统筹，张三负责场地。".getBytes(StandardCharsets.UTF_8)
        );

        MockMultipartFile spaceId = new MockMultipartFile(
                "spaceId",
                "",
                "text/plain",
                DEFAULT_SPACE_ID.getBytes(StandardCharsets.UTF_8)
        );

        MockMultipartFile documentType = new MockMultipartFile(
                "documentType",
                "",
                "text/plain",
                "prd".getBytes(StandardCharsets.UTF_8)
        );

        // 通过 multipart 接口以 PRD 业务类型导入 TXT 来源资料
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .file(documentType)
                        .file(textFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.importedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("imported"))
                .andExpect(jsonPath("$.data.results[0].document.kind").value("txt"))
                .andExpect(jsonPath("$.data.results[0].document.documentType").value("prd"))
                .andExpect(jsonPath("$.data.results[0].document.contentHash").isString());

        // 查询来源资料列表，验证 Controller 返回真实持久化结果
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(12))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("人员分工.txt"))
                .andExpect(jsonPath("$.data.items[0].documentType").value("prd"))
                .andExpect(jsonPath("$.data.items[0].excerpt").value("行政部负责统筹，张三负责场地。"))
                .andExpect(jsonPath("$.data.items[0].latestExtraction.status").value("not_started"))
                .andExpect(jsonPath("$.data.items[0].latestCompletedExtractionId").doesNotExist());
    }

    @Test
    void controllerPaginatesPersistedDocumentsWithMybatisPlusPlugin() throws Exception {
        List<MultipartFile> files = java.util.stream.IntStream.rangeClosed(1, 13)
                .mapToObj(index -> (MultipartFile) new MockMultipartFile(
                        "files",
                        "分页资料-%02d.txt".formatted(index),
                        "text/plain",
                        "第 %02d 份分页资料".formatted(index).getBytes(StandardCharsets.UTF_8)
                ))
                .toList();

        // 一次导入 13 份唯一资料，形成默认每页 12 条的两页数据
        documentService.importDocuments(
                DEFAULT_SPACE_ID,
                files
        );

        // 查询第一页，验证分页插件同时返回当前页记录和总数元数据
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(12))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(12))
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        // 查询第二页，验证接口不会回退为一次返回空间内全部资料
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("page", "2")
                        .queryParam("pageSize", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void controllerFiltersDocumentsByNameWithinCurrentSpace() throws Exception {
        MockMultipartFile chineseDocument = new MockMultipartFile(
                "files",
                "年度会议-Alpha计划.txt",
                "text/plain",
                "年度会议 Alpha 计划".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile specialCharacterDocument = new MockMultipartFile(
                "files",
                "年度会议-设计?.txt",
                "text/plain",
                "年度会议设计稿".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile wildcardCharacterDocument = new MockMultipartFile(
                "files",
                "预算_100%.txt",
                "text/plain",
                "预算一百百分比".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile otherSpaceDocument = new MockMultipartFile(
                "files",
                "年度会议-其他空间.txt",
                "text/plain",
                "其他空间中的同名资料".getBytes(StandardCharsets.UTF_8)
        );

        // 导入当前空间的中文、大小写和特殊字符名称资料
        documentService.importDocuments(
                DEFAULT_SPACE_ID,
                List.of(chineseDocument, specialCharacterDocument, wildcardCharacterDocument)
        );

        // 创建隔离空间并导入同一名称前缀资料，验证搜索不会跨空间召回
        KnowledgeSpaceResponse otherSpace = knowledgeSpaceService.createSpace(
                new CreateKnowledgeSpaceRequest("名称搜索隔离空间", "模糊搜索测试")
        );
        documentService.importDocuments(otherSpace.id(), List.of(otherSpaceDocument));

        // 使用小写英文查询，验证 SQLite 名称匹配保持大小写不敏感
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("年度会议-Alpha计划.txt"));

        // 使用中文名称前缀和每页一条查询，验证分页元数据与过滤条件同时生效
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "年度会议")
                        .queryParam("page", "2")
                        .queryParam("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        // 使用文件名中的问号，验证特殊字符通过参数绑定参与模糊匹配
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "设计?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("年度会议-设计?.txt"));

        // 搜索百分号和下划线时按文件名字符匹配，而不是展开成 LIKE 通配符
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("预算_100%.txt"));
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("预算_100%.txt"));

        // 查询当前空间不存在的名称，验证空结果仍保留分页结构
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("name", "不存在的资料"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // 清理测试创建的隔离空间，避免污染后续测试和本地演示数据
        knowledgeSpaceService.deleteSpace(otherSpace.id());
    }

    @Test
    void controllerValidatesPaginationParametersWithJakartaValidation() throws Exception {
        // 使用非法页码调用列表接口，验证 Jakarta Validation 返回统一 400 响应
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.msg").value("页码必须从 1 开始"));

        // 使用超过插件上限的每页数量，验证参数在进入 Service 前被拒绝
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.msg").value("每页数量不能超过 100"));
    }

    @Test
    void openApiPublishesDocumentPaginationAndExtractionSummaryModels() throws Exception {
        // 查询运行时 OpenAPI，验证分页响应和最近抽取摘要已进入接口契约
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/documents'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents'].get.parameters[*].name",
                        org.hamcrest.Matchers.hasItem("name")
                ))
                .andExpect(jsonPath("$.components.schemas.SourceDocumentPageResponse").exists())
                .andExpect(jsonPath("$.components.schemas.SourceDocumentExtractionSummary").exists())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/documents/deletion-batches'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/documents/extraction-batches'].post").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentBatchRequest").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentBatchDeleteResponse").exists())
                .andExpect(jsonPath("$.components.schemas.AiExtractionBatchResponse").exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/association-runs'].post"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/relations'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/relation-review-batches'].post"
                ).exists())
                .andExpect(jsonPath("$.components.schemas.DocumentAssociationRunResponse").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentRelationReviewBatchRequest").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentRelationResponse").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.SourceDocumentResponse.properties.kind.enum",
                        org.hamcrest.Matchers.hasItem("pdf")
                ));
    }

    @Test
    void controllerRejectsUnsupportedDocumentType() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "files",
                "范围说明.txt",
                "text/plain",
                "这是范围说明。".getBytes(StandardCharsets.UTF_8)
        );

        MockMultipartFile spaceId = new MockMultipartFile(
                "spaceId",
                "",
                "text/plain",
                DEFAULT_SPACE_ID.getBytes(StandardCharsets.UTF_8)
        );

        MockMultipartFile documentType = new MockMultipartFile(
                "documentType",
                "",
                "text/plain",
                "unknown".getBytes(StandardCharsets.UTF_8)
        );

        // 导入不支持的文档业务类型，验证参数错误不会创建批次或来源资料
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .file(documentType)
                        .file(textFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文档业务类型仅支持 general 或 prd"));

        // 验证参数失败没有产生来源资料
        assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).isEmpty();
    }

    @Test
    void controllerReturnsFileLevelFailureForMalformedUtf8() throws Exception {
        MockMultipartFile malformedFile = new MockMultipartFile(
                "files",
                "错误编码.txt",
                "text/plain",
                new byte[]{(byte) 0xC3, 0x28}
        );

        MockMultipartFile spaceId = new MockMultipartFile(
                "spaceId",
                "",
                "text/plain",
                DEFAULT_SPACE_ID.getBytes(StandardCharsets.UTF_8)
        );

        // 导入包含非法 UTF-8 字节的 TXT 文件
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .file(malformedFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("failed"))
                .andExpect(jsonPath("$.data.results[0].message").value("文件不是有效的 UTF-8 文本"))
                .andExpect(jsonPath("$.data.results[0].document").doesNotExist());

        // 验证解析失败不会创建来源资料记录
        assertThat(sourceDocumentRepository.findAll(DEFAULT_SPACE_ID)).isEmpty();
    }

    @Test
    void controllerReturnsPersistedMarkdownContentForPreview() throws Exception {
        MockMultipartFile markdownFile = new MockMultipartFile(
                "files",
                "预览资料.md",
                "text/markdown",
                "# 预览标题\n\n- 第一条内容\n- 第二条内容".getBytes(StandardCharsets.UTF_8)
        );

        // 先通过 Service 创建真实来源资料，得到后续预览接口需要的数据库标识
        DocumentImportResponse importResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                "prd",
                List.of(markdownFile)
        );
        String documentId = importResponse.results().getFirst().document().id();

        // 通过原文预览接口读取服务端保存的 Markdown 解析文本
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/content",
                        DEFAULT_SPACE_ID,
                        documentId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.name").value("预览资料.md"))
                .andExpect(jsonPath("$.data.kind").value("markdown"))
                .andExpect(jsonPath("$.data.documentType").value("prd"))
                .andExpect(jsonPath("$.data.contentText").value("# 预览标题\n\n- 第一条内容\n- 第二条内容"));
    }

    @Test
    void controllerSoftDeletesDocumentAndInvalidatesExclusiveGraphSources() throws Exception {
        String content = "# 删除测试\n该资料独立支撑一个图谱节点和关系。";
        MockMultipartFile documentFile = new MockMultipartFile(
                "files",
                "删除测试.md",
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // 导入待删除来源资料并记录原始文件路径
        DocumentImportResponse importResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                "prd",
                List.of(documentFile)
        );
        String documentId = importResponse.results().getFirst().document().id();
        SourceDocument savedDocument = sourceDocumentRepository.findById(DEFAULT_SPACE_ID, documentId)
                .orElseThrow();
        Instant createdAt = Instant.now();

        // 创建一个仅由待删除资料支撑的节点
        graphRepository.saveNode(new GraphNode(
                "delete-exclusive-node",
                DEFAULT_SPACE_ID,
                "document",
                "删除测试节点",
                "只由待删除资料支撑",
                "active",
                "document:delete-exclusive-node",
                List.of(documentId),
                createdAt,
                createdAt
        ));

        // 创建一个由其他来源保留的稳定节点
        graphRepository.saveNode(new GraphNode(
                "delete-stable-node",
                DEFAULT_SPACE_ID,
                "project",
                "稳定项目节点",
                "删除资料后仍保留",
                "active",
                "project:delete-stable-node",
                List.of("other-source"),
                createdAt,
                createdAt
        ));

        // 创建连接两个节点且证据仅来自待删除资料的关系
        graphRepository.saveEdge(new GraphEdge(
                "delete-test-edge",
                DEFAULT_SPACE_ID,
                "delete-exclusive-node",
                "delete-stable-node",
                "属于项目",
                "confirmed",
                1.0D,
                createdAt,
                createdAt
        ));
        graphRepository.saveEvidence(new GraphEvidence(
                "delete-test-evidence",
                DEFAULT_SPACE_ID,
                "delete-test-edge",
                documentId,
                "删除测试.md",
                "该资料独立支撑一个图谱节点和关系。",
                "第 2 行",
                "user",
                createdAt
        ));

        // 删除来源资料，验证接口返回成功
        mockMvc.perform(delete(
                        "/v1/spaces/{spaceId}/documents/{documentId}",
                        DEFAULT_SPACE_ID,
                        documentId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false));

        // 来源资料列表不再返回已删除记录
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        // 已删除资料不能继续通过原文接口预览
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/content",
                        DEFAULT_SPACE_ID,
                        documentId
                ))
                .andExpect(status().isNotFound());

        // 图谱中仅由删除资料支撑的节点和关系不再返回，其他来源节点继续保留
        mockMvc.perform(get("/v1/spaces/{spaceId}/graph", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(1))
                .andExpect(jsonPath("$.data.nodes[0].id").value("delete-stable-node"))
                .andExpect(jsonPath("$.data.edges.length()").value(0));

        // 物理原始文件和数据库事实记录继续保留
        assertThat(Path.of(savedDocument.storagePath())).exists();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM source_documents WHERE id = ?",
                String.class,
                documentId
        )).isEqualTo("deleted");

        // 重新上传同内容时恢复原记录，但不会盲目恢复已经失效的图谱节点和关系
        DocumentImportResponse restoredResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                "prd",
                List.of(documentFile)
        );
        assertThat(restoredResponse.importedCount()).isEqualTo(1);
        assertThat(restoredResponse.results().getFirst().document().id()).isEqualTo(documentId);
        assertThat(graphRepository.findNodes(DEFAULT_SPACE_ID))
                .extracting(GraphNode::id)
                .containsExactly("delete-stable-node");
    }

    @Test
    void controllerBatchDeletesSelectedDocumentsInOneRequest() throws Exception {
        MockMultipartFile firstFile = new MockMultipartFile(
                "files",
                "批量删除一.md",
                "text/markdown",
                "# 批量删除一\n\n第一份虚构来源资料。".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile secondFile = new MockMultipartFile(
                "files",
                "批量删除二.md",
                "text/markdown",
                "# 批量删除二\n\n第二份虚构来源资料。".getBytes(StandardCharsets.UTF_8)
        );

        // 导入两份来源资料，准备批量软删除请求的真实资料标识
        DocumentImportResponse importResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                "general",
                List.of(firstFile, secondFile)
        );
        List<String> documentIds = importResponse.results().stream()
                .map(result -> result.document().id())
                .toList();

        // 通过批量资源一次提交两份资料，验证服务端事务性软删除能力
        mockMvc.perform(post("/v1/spaces/{spaceId}/documents/deletion-batches", DEFAULT_SPACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[\"" + documentIds.get(0) + "\",\"" + documentIds.get(1) + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.deletedCount").value(2))
                .andExpect(jsonPath("$.data.documentIds.length()").value(2));

        // 查询列表确认两份资料均已从当前空间的有效资料中移除
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 查询数据库确认批量操作仍为软删除，历史事实记录没有被物理移除
        Integer deletedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_documents WHERE status = 'deleted'",
                Integer.class
        );
        assertThat(deletedCount).isEqualTo(2);
    }

    @Test
    void controllerRejectsRequestWithoutFiles() throws Exception {
        MockMultipartFile spaceId = new MockMultipartFile(
                "spaceId",
                "",
                "text/plain",
                DEFAULT_SPACE_ID.getBytes(StandardCharsets.UTF_8)
        );

        // 提交不包含 files 部件的 multipart 请求
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请选择需要导入的 Markdown、TXT 或文本型 PDF 文件"));
    }

    @Test
    void corsAllowsConfiguredFrontendOrigin() throws Exception {
        // 模拟本地前端对来源资料列表发起跨域预检请求
        mockMvc.perform(options("/v1/spaces/{spaceId}/documents", DEFAULT_SPACE_ID)
                        .header("Origin", "http://localhost:3010")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3010"));
    }

    @Test
    void corsAllowsConfiguredFrontendDelete() throws Exception {
        // 模拟前端删除知识空间前发起的 DELETE 跨域预检请求
        mockMvc.perform(options("/v1/spaces/default-space")
                        .header("Origin", "http://localhost:3010")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3010"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    /**
     * 删除测试生成的来源文件；清理失败时让测试直接失败并保留路径上下文。
     *
     * @param storagePath 测试来源文件路径
     */
    private void deleteTestFile(String storagePath) {
        try {
            // 删除上一用例保存的测试来源文件
            Files.deleteIfExists(Path.of(storagePath));
        } catch (IOException exception) {
            throw new IllegalStateException("无法清理测试来源文件: " + storagePath, exception);
        }
    }

    /**
     * 生成只包含标准字体虚构文本的 PDF；空值页面用于模拟扫描件或图片页。
     *
     * @param pageTexts 每页文本；空值表示页面不写入文本内容
     * @return 可供 multipart 测试上传的 PDF 原始字节
     * @throws IOException PDF 生成失败时抛出
     */
    private byte[] createTextPdf(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                // 逐页写入固定虚构文本，保持测试输入可重复
                addPdfPage(document, pageText);
            }

            // 将内存 PDF 序列化为原始字节，供导入链路计算真实 SHA-256
            return savePdf(document);
        }
    }

    /**
     * 生成需要用户密码才能打开的 PDF。
     *
     * @return 受密码保护的 PDF 原始字节
     * @throws IOException PDF 生成失败时抛出
     */
    private byte[] createEncryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            // 写入一页文本，确保失败原因来自密码保护而不是无文本
            addPdfPage(document, "Password protected fictional document.");

            AccessPermission accessPermission = new AccessPermission();
            StandardProtectionPolicy protectionPolicy = new StandardProtectionPolicy(
                    "owner-password",
                    "user-password",
                    accessPermission
            );
            protectionPolicy.setEncryptionKeyLength(128);

            // 对文档应用标准密码保护
            document.protect(protectionPolicy);

            // 序列化受保护 PDF
            return savePdf(document);
        }
    }

    /**
     * 生成具有合法 PDF 结构但不包含页面的文件。
     *
     * @return 零页 PDF 原始字节
     * @throws IOException PDF 生成失败时抛出
     */
    private byte[] createZeroPagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            // 直接保存未添加页面的 PDF，验证零页错误类型
            return savePdf(document);
        }
    }

    /**
     * 向 PDF 添加一页可选标准字体文本。
     *
     * @param document 待写入 PDF
     * @param pageText 页面文本；为空时保留空白页
     * @throws IOException 页面内容写入失败时抛出
     */
    private void addPdfPage(
            PDDocument document,
            String pageText
    ) throws IOException {
        PDPage page = new PDPage();

        // 将新页面加入 PDF 页面树
        document.addPage(page);
        if (pageText == null) {
            return;
        }

        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            // 使用 PDF 标准字体写入一行 ASCII 虚构文本，避免测试依赖外部字体文件
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(72, 720);
            contentStream.showText(pageText);
            contentStream.endText();
        }
    }

    /**
     * 将内存 PDF 保存为字节数组。
     *
     * @param document 待序列化 PDF
     * @return PDF 原始字节
     * @throws IOException 序列化失败时抛出
     */
    private byte[] savePdf(PDDocument document) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // 将完整 PDF 写入内存输出流
        document.save(outputStream);
        return outputStream.toByteArray();
    }
}
