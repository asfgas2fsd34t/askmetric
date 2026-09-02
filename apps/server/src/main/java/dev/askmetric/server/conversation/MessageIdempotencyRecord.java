package dev.askmetric.server.conversation;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户消息幂等请求的持久化记录。 */
@Data
@NoArgsConstructor
public class MessageIdempotencyRecord {
    /** 幂等记录的全局唯一标识。 */
    private String idempotencyId;
    /** 幂等键所属的 Workspace。 */
    private String workspaceId;
    /** 幂等键所属的 Conversation。 */
    private String conversationId;
    /** 发起请求的业务用户。 */
    private String userSubject;
    /** 客户端提供的请求幂等键。 */
    private String idempotencyKey;
    /** 规范化消息内容的 SHA-256 指纹。 */
    private String requestHash;
    /** 首次请求的完整响应；事务完成后写入。 */
    private String responseJson;
    /** 幂等记录创建时间。 */
    private Instant createdAt;
}
