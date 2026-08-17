package com.flevin.knowgraph.server.service.document;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 来源资料服务，负责文件导入、内容指纹识别和来源记录查询。
 */
public interface DocumentService {

    /**
     * 导入一批 Markdown/TXT 来源资料，逐文件返回成功、重复或失败结果。
     *
     * @param files 用户上传的来源资料；为空时返回参数提示
     * @return 带批次统计和逐文件结果的导入响应
     */
    DocumentImportResponse importDocuments(List<MultipartFile> files);

    /**
     * 查询当前数据库中已成功持久化的来源资料摘要。
     *
     * @return 按首次导入时间倒序排列的来源资料列表
     */
    List<SourceDocumentResponse> listDocuments();
}
