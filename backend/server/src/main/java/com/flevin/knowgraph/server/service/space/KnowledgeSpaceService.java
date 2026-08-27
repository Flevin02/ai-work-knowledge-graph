package com.flevin.knowgraph.server.service.space;

import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;

import java.util.List;

/**
 * 知识空间服务，负责空间生命周期和数据访问前的有效性校验。
 */
public interface KnowledgeSpaceService {

    /**
     * 查询全部有效知识空间。
     *
     * @return 有效知识空间列表
     */
    List<KnowledgeSpaceResponse> listSpaces();

    /**
     * 创建知识空间并准备对应的本地文件目录。
     *
     * @param request 创建请求
     * @return 新建知识空间
     */
    KnowledgeSpaceResponse createSpace(CreateKnowledgeSpaceRequest request);

    /**
     * 软删除知识空间；允许当前有效空间列表为空，且不删除事实来源。
     *
     * @param spaceId 待删除知识空间标识
     */
    void deleteSpace(Long spaceId);

    /**
     * 获取有效知识空间，不存在或已删除时返回业务提示。
     *
     * @param spaceId 知识空间标识
     * @return 有效知识空间模型
     */
    KnowledgeSpace requireActive(Long spaceId);
}
