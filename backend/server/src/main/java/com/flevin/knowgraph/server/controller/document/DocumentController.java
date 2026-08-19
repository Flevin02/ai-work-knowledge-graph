package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentContentResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentPageResponse;
import com.flevin.knowgraph.server.service.ai.AiExtractionEventPublisher;
import com.flevin.knowgraph.server.service.ai.AiExtractionService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@RequestMapping("/v1/spaces/{spaceId}/documents")
@Tag(name = "来源资料", description = "导入和查询作为知识图谱事实源的办公资料")
@RequiredArgsConstructor
@Validated
public class DocumentController {

	private final DocumentService documentService;
	private final AiExtractionService aiExtractionService;
	private final AiExtractionSseWriter aiExtractionSseWriter;

	@GetMapping(value = "", name = "查询来源资料列表")
	@Operation(summary = "查询来源资料列表", description = "分页返回来源资料及最近一次 AI 抽取摘要，默认每页 12 条。")
	public ApiResponse<SourceDocumentPageResponse> listDocuments(
			@Parameter(description = "知识空间标识", example = "default-space")
			@PathVariable String spaceId,
			@Parameter(description = "页码，从 1 开始", example = "1")
			@Min(value = 1, message = "页码必须从 1 开始")
			@RequestParam(defaultValue = "1") int page,
			@Parameter(description = "每页数量，最大 100", example = "12")
			@Min(value = 1, message = "每页数量必须至少为 1")
			@Max(value = 100, message = "每页数量不能超过 100")
			@RequestParam(defaultValue = "12") int pageSize
	) {
		// 分页查询指定知识空间的来源资料和最近 AI 抽取摘要
		SourceDocumentPageResponse documents = documentService.listDocuments(
				spaceId,
				page,
				pageSize
		);

		// 使用统一响应结构返回来源资料列表
		return ApiResponse.success(documents);
	}

	@GetMapping(value = "/{documentId}/content", name = "预览来源资料原文")
	@Operation(summary = "预览来源资料原文", description = "返回当前知识空间内来源资料的解析文本，不暴露服务端存储路径。")
	public ApiResponse<SourceDocumentContentResponse> getDocumentContent(
			@Parameter(description = "知识空间标识", example = "default-space")
			@PathVariable String spaceId,
			@Parameter(description = "来源资料标识")
			@PathVariable String documentId
	) {
		// 查询指定知识空间内来源资料的安全原文预览
		SourceDocumentContentResponse response = documentService.getDocumentContent(spaceId, documentId);

		// 使用统一响应结构返回预览内容和来源元数据
		return ApiResponse.success(response);
	}

	@PostMapping(
			value = "/{documentId}/extractions",
			name = "创建来源资料 AI 抽取",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE
	)
	@Operation(
			summary = "创建来源资料 AI 抽取",
			description = "通过 SSE 返回运行、分片、真实模型增量、完成或失败事件；完整结果通过结构和证据校验后才保存，当前阶段不直接写入正式图谱。"
	)
	public ResponseEntity<StreamingResponseBody> extractDocument(
				@Parameter(description = "知识空间标识", example = "default-space")
				@PathVariable String spaceId,
				@Parameter(description = "来源资料标识")
				@PathVariable String documentId
		) {
			StreamingResponseBody responseBody = outputStream -> {
				// 为当前 HTTP 输出流创建断线安全的 SSE 事件发布器
				AiExtractionEventPublisher eventPublisher = aiExtractionSseWriter.createPublisher(outputStream);

				// 执行可持久化恢复的流式抽取，完整结果或错误均通过终止事件返回
				aiExtractionService.streamDocument(
						spaceId,
						documentId,
						eventPublisher
				);
			};

			// 禁止中间代理缓冲 SSE，并允许前端按事件到达顺序增量消费
			return ResponseEntity.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.header(HttpHeaders.CACHE_CONTROL, "no-cache")
					.header("X-Accel-Buffering", "no")
					.body(responseBody);
	}

    @GetMapping(value = "/{documentId}/extractions", name = "查询来源资料 AI 抽取记录")
    @Operation(summary = "查询来源资料 AI 抽取记录", description = "返回指定来源资料历史抽取记录摘要，最新记录优先。")
    public ApiResponse<List<AiExtractionRunSummary>> listExtractions(
            @Parameter(description = "知识空间标识", example = "default-space")
            @PathVariable String spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable String documentId
    ) {
        // 查询指定来源资料的历史 AI 抽取记录
        List<AiExtractionRunSummary> responses = aiExtractionService.listExtractions(
                spaceId,
                documentId
        );

        // 使用统一响应结构返回抽取记录摘要
        return ApiResponse.success(responses);
    }

    @GetMapping(value = "/{documentId}/extractions/{extractionId}", name = "查询来源资料 AI 抽取结果")
    @Operation(summary = "查询来源资料 AI 抽取结果", description = "返回指定抽取记录的状态、错误摘要和完整候选结果。")
    public ApiResponse<AiExtractionRunDetail> getExtraction(
            @Parameter(description = "知识空间标识", example = "default-space")
            @PathVariable String spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable String documentId,
            @Parameter(description = "AI 抽取记录标识")
            @PathVariable String extractionId
    ) {
        // 查询指定历史抽取记录及其完整候选结果
        AiExtractionRunDetail response = aiExtractionService.getExtraction(
                spaceId,
                documentId,
                extractionId
        );

        // 使用统一响应结构返回抽取运行详情
        return ApiResponse.success(response);
    }

	@PostMapping(
			value = "",
			name = "创建文本型来源资料",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE
	)
	@Operation(
			summary = "创建 Markdown、TXT 或文本型 PDF 来源资料",
			description = "逐文件解析 UTF-8 文本或带页码边界的 PDF 文本、计算原始字节的 SHA-256 内容指纹，并返回成功、重复或失败结果；扫描 PDF 暂不支持 OCR。"
	)
	public ApiResponse<DocumentImportResponse> importDocuments(
			@Parameter(description = "知识空间标识", example = "default-space")
			@PathVariable String spaceId,
			@Parameter(description = "文档业务类型；未提供时按 general 处理", example = "prd")
			@RequestPart(value = "documentType", required = false) String documentType,
			@Parameter(description = "待导入的 Markdown、TXT 或文本型 PDF 文件，可一次选择多份")
			@RequestPart(value = "files", required = false) List<MultipartFile> files
	) {
		// 在指定知识空间执行来源资料批量导入和重复内容识别
		DocumentImportResponse response = documentService.importDocuments(spaceId, documentType, files);

		// 使用统一响应结构返回批次和逐文件处理结果
		return ApiResponse.success(response);
	}

	@DeleteMapping(value = "/{documentId}", name = "删除来源资料")
	@Operation(
			summary = "删除来源资料",
			description = "软删除来源资料，并同步失效仅由该资料支撑的图谱节点和关系；原始文件和证据记录继续保留。"
	)
	public ApiResponse<Void> deleteDocument(
			@Parameter(description = "知识空间标识", example = "default-space")
			@PathVariable String spaceId,
			@Parameter(description = "来源资料标识")
			@PathVariable String documentId
	) {
		// 软删除来源资料并同步处理图谱来源贡献
		documentService.deleteDocument(spaceId, documentId);

		// 使用统一响应结构返回无业务数据的成功结果
		return ApiResponse.success(null);
	}
}
