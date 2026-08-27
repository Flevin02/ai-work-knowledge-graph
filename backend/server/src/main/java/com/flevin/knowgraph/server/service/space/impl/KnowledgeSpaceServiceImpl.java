package com.flevin.knowgraph.server.service.space.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.BusinessException;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceResponseMapper;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.storage.LocalFileStorage;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 知识空间服务实现，使用软删除保护空间下的来源资料和图谱事实。
 */
@Service
public class KnowledgeSpaceServiceImpl implements KnowledgeSpaceService {

    private static final String ACTIVE_STATUS = "active";

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final LocalFileStorage localFileStorage;
    private final KnowledgeSpaceResponseMapper responseMapper;

    public KnowledgeSpaceServiceImpl(
            KnowledgeSpaceRepository knowledgeSpaceRepository,
            LocalFileStorage localFileStorage,
            KnowledgeSpaceResponseMapper responseMapper
    ) {
        this.knowledgeSpaceRepository = knowledgeSpaceRepository;
        this.localFileStorage = localFileStorage;
        this.responseMapper = responseMapper;
    }

    /**
     * 查询全部有效知识空间。
     *
     * @return 有效知识空间列表
     */
    @Override
    public List<KnowledgeSpaceResponse> listSpaces() {
        // 查询有效空间并转换为接口响应
        return knowledgeSpaceRepository.findAllActive().stream()
                .map(responseMapper::toResponse)
                .toList();
    }

    /**
     * 创建知识空间并准备对应的本地文件目录。
     *
     * @param request 创建请求
     * @return 新建知识空间
     */
    @Override
    public KnowledgeSpaceResponse createSpace(CreateKnowledgeSpaceRequest request) {
        // 清理知识空间名称两侧空白，统一同名判断口径
        String normalizedName = request.name().strip();
        if (knowledgeSpaceRepository.existsActiveByName(normalizedName)) {
            throw new TipsException(ErrorCode.DATA_ALREADY_EXISTS, "已存在同名知识空间");
        }

        // 清理可选说明两侧空白，空白说明按空值保存
        String normalizedDescription = normalizeDescription(request.description());

        // 创建知识空间 Long 唯一标识，供后续资料、关系和审核记录复用
        Long spaceId = SnowflakeIdGenerator.nextId();

        // 获取知识空间统一创建和更新时间
        Instant createdAt = Instant.now();

        try {
            // 在写入空间记录前准备独立来源资料目录
            localFileStorage.prepareDocumentsDirectory(spaceId);
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "无法创建知识空间本地目录: " + normalizedName,
                    exception
            );
        }

        KnowledgeSpace space = new KnowledgeSpace(
                spaceId,
                normalizedName,
                normalizedDescription,
                ACTIVE_STATUS,
                createdAt,
                createdAt
        );

        // 保存新知识空间
        knowledgeSpaceRepository.save(space);
        // 将新建领域模型转换为接口响应
        return responseMapper.toResponse(space);
    }

    /**
     * 软删除知识空间；允许当前有效空间列表为空，且不删除事实来源。
     *
     * @param spaceId 待删除知识空间标识
     */
    @Override
    public void deleteSpace(Long spaceId) {
        // 校验待删除空间当前仍然有效
        requireActive(spaceId);

        // 仅软删除空间记录，不清理其目录、来源资料或图谱事实
        int updatedRows = knowledgeSpaceRepository.softDelete(spaceId, Instant.now());
        if (updatedRows == 0) {
            throw new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在或已删除");
        }
    }

    /**
     * 获取有效知识空间，不存在或已删除时返回业务提示。
     *
     * @param spaceId 知识空间标识
     * @return 有效知识空间模型
     */
    @Override
    public KnowledgeSpace requireActive(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "请选择知识空间");
        }

        // 查询指定知识空间并拒绝访问已删除空间
        return knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在或已删除"));
    }

    /**
     * 规范化可选知识空间说明。
     *
     * @param description 原始说明
     * @return 规范化说明；空白时返回空值
     */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        // 去除说明两侧无意义空白
        return description.strip();
    }

}
