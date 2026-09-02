package dev.askmetric.server.conversation;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户消息幂等记录的工作区隔离持久化查询。 */
@Mapper
public interface MessageIdempotencyMapper {
    /** 预留一次消息请求；重复幂等键不会创建第二条记录。 */
    @Insert("""
            insert into message_idempotency (
                idempotency_id, workspace_id, conversation_id, user_subject,
                idempotency_key, request_hash
            )
            select #{idempotencyId}, conversation.workspace_id, conversation.conversation_id,
                   #{userSubject}, #{idempotencyKey}, #{requestHash}
            from conversation
            join workspace_membership membership on membership.workspace_id = conversation.workspace_id
            where conversation.conversation_id = #{conversationId}
              and conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            on conflict (workspace_id, conversation_id, user_subject, idempotency_key) do nothing
            """)
    int reserve(
            @Param("idempotencyId") String idempotencyId,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("userSubject") String userSubject,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash);

    @Results(id = "messageIdempotency", value = {
            @Result(column = "idempotency_id", property = "idempotencyId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "user_subject", property = "userSubject"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "request_hash", property = "requestHash"),
            @Result(column = "response_json", property = "responseJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select idempotency_id, workspace_id, conversation_id, user_subject,
                   idempotency_key, request_hash, response_json, created_at
            from message_idempotency
            where workspace_id = #{workspaceId}
              and conversation_id = #{conversationId}
              and user_subject = #{userSubject}
              and idempotency_key = #{idempotencyKey}
            """)
    Optional<MessageIdempotencyRecord> find(
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("userSubject") String userSubject,
            @Param("idempotencyKey") String idempotencyKey);

    /** 保存首次请求的响应，使重试可以返回完全相同的业务结果。 */
    @Update("""
            update message_idempotency
            set response_json = #{responseJson}
            where idempotency_id = #{idempotencyId}
            """)
    int complete(@Param("idempotencyId") String idempotencyId, @Param("responseJson") String responseJson);
}
