package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentResponse;
import com.flevin.knowgraph.server.service.document.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/v1/documents")
@Tag(name = "来源资料", description = "导入和查询作为知识图谱事实源的办公资料")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping(value = "", name = "查询来源资料列表")
    @Operation(summary = "查询来源资料列表", description = "返回已经成功解析并持久化的来源资料摘要。")
    public ApiResponse<List<SourceDocumentResponse>> listDocuments() {
        // 查询已持久化的来源资料摘要
        List<SourceDocumentResponse> documents = documentService.listDocuments();

        // 使用统一响应结构返回来源资料列表
        return ApiResponse.success(documents);
    }

    @PostMapping(
            value = "/import",
            name = "导入 Markdown 或 TXT 来源资料",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            summary = "导入 Markdown 或 TXT 来源资料",
            description = "逐文件读取 UTF-8 文本、计算 SHA-256 内容指纹，并返回成功、重复或失败结果。"
    )
    public ApiResponse<DocumentImportResponse> importDocuments(
            @Parameter(description = "待导入的 Markdown/TXT 文件，可一次选择多份")
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        // 执行来源资料批量导入和重复内容识别
        DocumentImportResponse response = documentService.importDocuments(files);

        // 使用统一响应结构返回批次和逐文件处理结果
        return ApiResponse.success(response);
    }
}
