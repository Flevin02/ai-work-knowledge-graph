package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 只读问答会话消息 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("conversation_messages")
public class ConversationMessageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("conversation_id")
    private Long conversationId;

    private String role;

    private String content;

    private String status;

    @TableField("grounding_status")
    private String groundingStatus;

    @TableField("error_category")
    private String errorCategory;

    @TableField("error_message")
    private String errorMessage;

    @TableField("answer_client")
    private String answerClient;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("schema_version")
    private String schemaVersion;

    @TableField("citation_count")
    private Integer citationCount;

    @TableField("citation_failure_count")
    private Integer citationFailureCount;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private String createdAt;
}
