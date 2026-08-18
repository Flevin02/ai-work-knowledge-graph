package com.flevin.knowgraph.server.service.document;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentContentResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentPageResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 来源资料服务，负责文件导入、内容指纹识别和来源记录查询。
 */
public interface DocumentService {

    /**
     * 以通用文档类型导入一批 Markdown/TXT 来源资料。
     *
     * @param spaceId 知识空间标识
     * @param files 用户上传的来源资料；为空时返回参数提示
     * @return 带批次统计和逐文件结果的导入响应
     */
    default DocumentImportResponse importDocuments(
            String spaceId,
            List<MultipartFile> files
    ) {
        // 使用稳定的 general 类型保持现有调用方兼容
        return importDocuments(spaceId, SourceDocumentType.GENERAL.getValue(), files);
    }

    /**
     * 导入一批 Markdown/TXT 来源资料，逐文件返回成功、重复或失败结果。
     *
     * @param spaceId 知识空间标识
     * @param documentType 文档业务类型；为空时按 general 处理
     * @param files 用户上传的来源资料；为空时返回参数提示
     * @return 带批次统计和逐文件结果的导入响应
     */
    DocumentImportResponse importDocuments(
            String spaceId,
            String documentType,
            List<MultipartFile> files
    );

    /**
     * 分页查询已成功持久化的来源资料及最近 AI 抽取摘要。
     *
     * @param spaceId 知识空间标识
     * @param page 页码，从 1 开始
     * @param pageSize 每页数量，最大 100
     * @return 按最近更新时间倒序排列的来源资料分页结果
     */
    SourceDocumentPageResponse listDocuments(
            String spaceId,
            int page,
            int pageSize
    );

    /**
     * 查询指定来源资料的解析原文，用于安全的纯文本预览。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 不暴露服务端路径的原文预览响应
     */
    SourceDocumentContentResponse getDocumentContent(
            String spaceId,
            String documentId
    );

    /**
     * 软删除来源资料，并同步失效无剩余来源支撑的图谱节点和关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     */
    void deleteDocument(
            String spaceId,
            String documentId
    );
}
