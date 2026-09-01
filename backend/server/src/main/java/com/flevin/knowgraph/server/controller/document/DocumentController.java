package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionBatchResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewRequest;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewResponse;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewState;
import com.flevin.knowgraph.server.model.document.DocumentBatchDeleteResponse;
import com.flevin.knowgraph.server.model.document.DocumentBatchRequest;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentContentResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentPageResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTagBatchReviewRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTagBatchReviewResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingBatchResponse;
import com.flevin.knowgraph.server.service.ai.AiExtractionEventPublisher;
import com.flevin.knowgraph.server.service.ai.AiExtractionService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.tag.DocumentTagService;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingService;
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
import org.springframework.web.bind.annotation.RequestBody;
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
	private final DocumentTaggingService documentTaggingService;
	private final DocumentTagService documentTagService;

	@GetMapping(value = "", name = "查询来源资料列表")
	@Operation(summary = "查询来源资料列表", description = "分页返回来源资料及最近一次 AI 抽取摘要，默认每页 12 条。")
	public ApiResponse<SourceDocumentPageResponse> listDocuments(
			@Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
			@PathVariable Long spaceId,
			@Parameter(description = "按原始文件名模糊查询；为空时返回当前空间全部资料", example = "会议")
			@RequestParam(required = false) String name,
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
				name,
				page,
				pageSize
		);

		// 使用统一响应结构返回来源资料列表
		return ApiResponse.success(documents);
	}

	@GetMapping(value = "/{documentId}/content", name = "预览来源资料原文")
	@Operation(summary = "预览来源资料原文", description = "返回当前知识空间内来源资料的解析文本，不暴露服务端存储路径。")
	public ApiResponse<SourceDocumentContentResponse> getDocumentContent(
			@Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
			@PathVariable Long spaceId,
			@Parameter(description = "来源资料标识")
			@PathVariable Long documentId
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
			description = "通过 SSE 返回运行、分片、真实模型增量、完成或失败事件；完整结果通过结构和证据校验后保存，并物化为待人工审核的候选图谱事实。"
	)
	public ResponseEntity<StreamingResponseBody> extractDocument(
				@Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
				@PathVariable Long spaceId,
				@Parameter(description = "来源资料标识")
				@PathVariable Long documentId
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

    @PostMapping(value = "/extraction-batches", name = "创建来源资料批量 AI 抽取")
    @Operation(
            summary = "创建来源资料批量 AI 抽取",
            description = "一次受理多份来源资料，并由服务端有界线程池并发执行独立抽取运行；每份资料仍通过列表状态和历史结果单独恢复。"
    )
    public ApiResponse<AiExtractionBatchResponse> submitBatchExtraction(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @jakarta.validation.Valid @RequestBody DocumentBatchRequest request
    ) {
        // 将多个来源资料提交到受控后台线程池，避免浏览器维持多条 SSE 连接
        AiExtractionBatchResponse response = aiExtractionService.submitBatchExtraction(
                spaceId,
                request.documentIds()
        );

        // 使用统一响应结构返回已受理和因队列繁忙未受理的资料标识
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/tagging-batches", name = "创建来源资料批量标签运行")
    @Operation(
            summary = "创建来源资料批量标签运行",
            description = "一次受理多份来源资料，并由服务端有界线程池并发执行独立标签运行；每份资料仍通过最近运行接口单独恢复。"
    )
    public ApiResponse<DocumentTaggingBatchResponse> submitBatchTagging(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @jakarta.validation.Valid @RequestBody DocumentBatchRequest request
    ) {
        // 将多份来源资料提交到受控后台线程池，逐份创建独立标签运行
        DocumentTaggingBatchResponse response = documentTaggingService.submitBatchTagging(
                spaceId,
                request.documentIds()
        );

        // 使用统一响应结构返回已受理和因队列繁忙未受理的资料标识
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/tag-review-batches", name = "跨文档批量审核文档标签")
    @Operation(
            summary = "跨文档批量审核文档标签",
            description = "对所选资料的 suggested 标签执行统一采纳或拒绝动作；只处理仍处于待审核状态的候选，已完成审核的资料自动跳过。"
    )
    public ApiResponse<DocumentTagBatchReviewResponse> reviewTagsAcrossDocuments(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @jakarta.validation.Valid @RequestBody DocumentTagBatchReviewRequest request
    ) {
        // 按统一动作逐份执行 suggested 到 confirmed/rejected 的状态迁移
        DocumentTagBatchReviewResponse response = documentTagService.reviewBatchAcrossDocuments(
                spaceId,
                request
        );

        // 使用统一响应结构返回本次跨文档审核统计
        return ApiResponse.success(response);
    }

    @GetMapping(value = "/{documentId}/extractions", name = "查询来源资料 AI 抽取记录")
    @Operation(summary = "查询来源资料 AI 抽取记录", description = "返回指定来源资料历史抽取记录摘要，最新记录优先。")
    public ApiResponse<List<AiExtractionRunSummary>> listExtractions(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable Long documentId
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
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable Long documentId,
            @Parameter(description = "AI 抽取记录标识")
            @PathVariable Long extractionId
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

    @PostMapping(value = "/{documentId}/extractions/{extractionId}/reviews", name = "审核来源资料 AI 候选关系")
    @Operation(
            summary = "审核来源资料 AI 候选关系",
            description = "按服务端保存的抽取结果校验分片和关系顺序，批量更新关系状态并记录 review_actions。"
    )
    public ApiResponse<AiRelationReviewResponse> reviewExtractionRelations(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable Long documentId,
            @Parameter(description = "AI 抽取运行标识")
            @PathVariable Long extractionId,
            @RequestBody @jakarta.validation.Valid AiRelationReviewRequest request
    ) {
        // 按抽取运行的服务端候选结果执行单条或批量关系审核
        AiRelationReviewResponse response = aiExtractionService.reviewRelations(
                spaceId,
                documentId,
                extractionId,
                request
        );

        // 使用统一响应结构返回本次审核统计
        return ApiResponse.success(response);
    }

    @GetMapping(value = "/{documentId}/extractions/{extractionId}/reviews", name = "查询 AI 候选关系审核状态")
    @Operation(
            summary = "查询 AI 候选关系审核状态",
            description = "恢复指定抽取结果已经持久化的采纳或拒绝决定，供弹窗刷新后继续展示。"
    )
    public ApiResponse<List<AiRelationReviewState>> listReviewStates(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识")
            @PathVariable Long documentId,
            @Parameter(description = "AI 抽取运行标识")
            @PathVariable Long extractionId
    ) {
        // 查询服务端已保存的候选关系审核状态
        List<AiRelationReviewState> response = aiExtractionService.listReviewStates(
                spaceId,
                documentId,
                extractionId
        );

        // 使用统一响应结构返回审核状态列表
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
			@Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
			@PathVariable Long spaceId,
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

    @PostMapping(value = "/deletion-batches", name = "创建来源资料批量删除")
    @Operation(
            summary = "创建来源资料批量删除",
            description = "在一个事务中软删除多份来源资料，并同步失效仅由这些资料支撑的图谱节点和关系；原始文件和历史证据继续保留。"
    )
    public ApiResponse<DocumentBatchDeleteResponse> deleteDocuments(
            @Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
            @PathVariable Long spaceId,
            @jakarta.validation.Valid @RequestBody DocumentBatchRequest request
    ) {
        // 批量软删除当前空间中已选择的来源资料，并同步图谱来源贡献
        DocumentBatchDeleteResponse response = documentService.deleteDocuments(
                spaceId,
                request.documentIds()
        );

        // 使用统一响应结构返回本批已删除的资料数量和资料标识
        return ApiResponse.success(response);
    }

	@DeleteMapping(value = "/{documentId}", name = "删除来源资料")
	@Operation(
			summary = "删除来源资料",
			description = "软删除来源资料，并同步失效仅由该资料支撑的图谱节点和关系；原始文件和证据记录继续保留。"
	)
	public ApiResponse<Void> deleteDocument(
			@Parameter(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
			@PathVariable Long spaceId,
			@Parameter(description = "来源资料标识")
			@PathVariable Long documentId
	) {
		// 软删除来源资料并同步处理图谱来源贡献
		documentService.deleteDocument(spaceId, documentId);

		// 使用统一响应结构返回无业务数据的成功结果
		return ApiResponse.success(null);
	}
}
