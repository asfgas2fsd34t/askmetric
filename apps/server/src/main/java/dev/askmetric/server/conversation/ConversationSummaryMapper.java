package dev.askmetric.server.conversation;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationSummaryMapper {
    /** 为当前用户 Workspace 内的对话追加一个版本的摘要。 */
    @Insert("""
            insert into conversation_summary (
                conversation_summary_id, workspace_id, conversation_id, version,
                from_sequence, to_sequence, summary_text, source_agent_run_id
            )
            select #{conversationSummaryId}, conversation.workspace_id, conversation.conversation_id,
                   next_version.next, #{fromSequence}, #{toSequence}, #{summaryText}, #{sourceAgentRunId}
            from conversation
            join workspace_membership membership on membership.workspace_id = conversation.workspace_id
            join lateral (
                select coalesce(max(existing.version), 0) + 1 as next
                from conversation_summary existing
                where existing.conversation_id = conversation.conversation_id
            ) next_version on true
            where conversation.conversation_id = #{conversationId}
              and conversation.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int insert(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId,
            @Param("conversationSummaryId") String conversationSummaryId,
            @Param("fromSequence") long fromSequence,
            @Param("toSequence") long toSequence,
            @Param("summaryText") String summaryText,
            @Param("sourceAgentRunId") String sourceAgentRunId);

    /** 读取对话的摘要，按版本倒序。 */
    @Results(id = "conversationSummary", value = {
            @Result(column = "conversation_summary_id", property = "conversationSummaryId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "from_sequence", property = "fromSequence"),
            @Result(column = "to_sequence", property = "toSequence"),
            @Result(column = "summary_text", property = "summaryText"),
            @Result(column = "source_agent_run_id", property = "sourceAgentRunId"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select summary.conversation_summary_id, summary.workspace_id, summary.conversation_id,
                   summary.version, summary.from_sequence, summary.to_sequence,
                   summary.summary_text, summary.source_agent_run_id, summary.created_at
            from conversation_summary summary
            join workspace_membership membership on membership.workspace_id = summary.workspace_id
            where summary.conversation_id = #{conversationId}
              and summary.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by summary.version desc
            """)
    List<ConversationSummary> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("conversationId") String conversationId);
}
