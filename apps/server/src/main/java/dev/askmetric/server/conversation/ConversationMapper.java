package dev.askmetric.server.conversation;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
interface ConversationMapper {
    @Results(id = "conversationSummary", value = {
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "message_count", property = "messageCount"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    @Select("""
            select conversation.conversation_id, conversation.title,
                   count(message.message_id) as message_count,
                   conversation.created_at, conversation.updated_at
            from conversation
            join workspace_membership membership
              on membership.workspace_id = conversation.workspace_id
            left join conversation_message message
              on message.conversation_id = conversation.conversation_id
            where conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            group by conversation.conversation_id
            order by conversation.updated_at desc, conversation.conversation_id
            """)
    List<ConversationSummary> list(String userSubject, String workspaceId);

    @Results(id = "conversationSnapshot", value = {
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    @Select("""
            insert into conversation (
                conversation_id, workspace_id, title, created_by_subject
            )
            select #{conversationId}, #{workspaceId}, #{title}, #{userSubject}
            from workspace_membership membership
            where membership.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            returning conversation_id, workspace_id, title, created_at, updated_at
            """)
    Optional<ConversationSnapshot> create(
            String userSubject, String workspaceId, String conversationId, String title);

    @ResultMap("conversationSnapshot")
    @Select("""
            select conversation.conversation_id, conversation.workspace_id, conversation.title,
                   conversation.created_at, conversation.updated_at
            from conversation
            join workspace_membership membership
              on membership.workspace_id = conversation.workspace_id
            where conversation.conversation_id = #{conversationId}
              and conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    Optional<ConversationSnapshot> find(String userSubject, String workspaceId, String conversationId);

    @Results(id = "conversationMessage", value = {
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "author_subject", property = "authorSubject"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            with allocated as (
                update conversation
                set next_message_sequence = next_message_sequence + 1,
                    updated_at = current_timestamp
                where conversation_id = #{conversationId}
                  and workspace_id = #{workspaceId}
                  and exists (
                      select 1
                      from workspace_membership membership
                      where membership.workspace_id = conversation.workspace_id
                        and membership.user_subject = #{userSubject}
                  )
                returning next_message_sequence - 1 as sequence
            )
            insert into conversation_message (
                message_id, conversation_id, author, author_subject, sequence, content
            )
            select #{messageId}, #{conversationId}, 'user', #{userSubject}, allocated.sequence, #{content}
            from allocated
            returning message_id, conversation_id, author, author_subject, sequence, content, created_at
            """)
    Optional<ConversationMessage> appendUserMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            String messageId,
            String content);

    @ResultMap("conversationMessage")
    @Select("""
            select message.message_id, message.conversation_id, message.author,
                   message.author_subject, message.sequence, message.content, message.created_at
            from conversation_message message
            join conversation on conversation.conversation_id = message.conversation_id
            join workspace_membership membership
              on membership.workspace_id = conversation.workspace_id
            where conversation.conversation_id = #{conversationId}
              and conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by message.sequence
            """)
    List<ConversationMessage> messages(String userSubject, String workspaceId, String conversationId);
}
