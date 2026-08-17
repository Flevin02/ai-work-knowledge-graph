package com.flevin.knowgraph.server.document;

import com.flevin.knowgraph.server.model.document.DocumentImportFileStatus;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/document-import.sqlite",
        "app.upload-dir=target/test-data/document-uploads"
})
@AutoConfigureMockMvc
class DocumentImportIntegrationTests {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

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

        // 先删除来源资料，满足 SQLite 外键约束
        jdbcTemplate.update("DELETE FROM source_documents");

        // 再删除已失去引用的导入批次
        jdbcTemplate.update("DELETE FROM import_batches");
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
        DocumentImportResponse firstResponse = documentService.importDocuments(List.of(firstFile));

        assertThat(firstResponse.importedCount()).isEqualTo(1);
        assertThat(firstResponse.duplicateCount()).isZero();
        assertThat(firstResponse.failedCount()).isZero();
        assertThat(firstResponse.results().getFirst().status()).isEqualTo(DocumentImportFileStatus.IMPORTED);

        // 通过 Repository 查询首次导入的来源资料
        List<SourceDocument> savedDocuments = sourceDocumentRepository.findAll();

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
        DocumentImportResponse duplicateResponse = documentService.importDocuments(List.of(duplicateFile));

        assertThat(duplicateResponse.importedCount()).isZero();
        assertThat(duplicateResponse.duplicateCount()).isEqualTo(1);
        assertThat(duplicateResponse.results().getFirst().status()).isEqualTo(DocumentImportFileStatus.DUPLICATE);

        // 再次查询 Repository，确认重复内容未新增来源记录
        assertThat(sourceDocumentRepository.findAll()).hasSize(1);
    }

    @Test
    void controllerImportsAndListsPersistedTextDocument() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "files",
                "人员分工.txt",
                "text/plain",
                "行政部负责统筹，张三负责场地。".getBytes(StandardCharsets.UTF_8)
        );

        // 通过 multipart 接口导入 TXT 来源资料
        mockMvc.perform(multipart("/v1/documents/import").file(textFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.importedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("imported"))
                .andExpect(jsonPath("$.data.results[0].document.kind").value("txt"))
                .andExpect(jsonPath("$.data.results[0].document.contentHash").isString());

        // 查询来源资料列表，验证 Controller 返回真实持久化结果
        mockMvc.perform(get("/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("人员分工.txt"))
                .andExpect(jsonPath("$.data[0].excerpt").value("行政部负责统筹，张三负责场地。"));
    }

    @Test
    void controllerReturnsFileLevelFailureForMalformedUtf8() throws Exception {
        MockMultipartFile malformedFile = new MockMultipartFile(
                "files",
                "错误编码.txt",
                "text/plain",
                new byte[]{(byte) 0xC3, 0x28}
        );

        // 导入包含非法 UTF-8 字节的 TXT 文件
        mockMvc.perform(multipart("/v1/documents/import").file(malformedFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("failed"))
                .andExpect(jsonPath("$.data.results[0].message").value("文件不是有效的 UTF-8 文本"))
                .andExpect(jsonPath("$.data.results[0].document").doesNotExist());

        // 验证解析失败不会创建来源资料记录
        assertThat(sourceDocumentRepository.findAll()).isEmpty();
    }

    @Test
    void controllerRejectsRequestWithoutFiles() throws Exception {
        // 提交不包含 files 部件的 multipart 请求
        mockMvc.perform(multipart("/v1/documents/import"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请选择需要导入的 Markdown 或 TXT 文件"));
    }

    @Test
    void corsAllowsConfiguredFrontendOrigin() throws Exception {
        // 模拟本地前端对来源资料列表发起跨域预检请求
        mockMvc.perform(options("/v1/documents")
                        .header("Origin", "http://localhost:3010")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3010"));
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
}
