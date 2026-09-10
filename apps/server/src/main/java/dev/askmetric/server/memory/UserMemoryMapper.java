package dev.askmetric.server.memory;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMemoryMapper {
    /** 登记一条待确认记忆；未确认条目不得被任何 Agent 上下文读取。 */
    @Insert("""
            insert into user_memory (
                user_memory_id, workspace_id, user_subject, content,
                status, source_conversation_id, source_agent_run_id
            )
            select #{userMemoryId}, workspace.workspace_id, #{userSubject}, #{content},
                   'PROPOSED', #{sourceConversationId}, #{sourceAgentRunId}
            from workspace_membership membership
            join workspace workspace on workspace.workspace_id = membership.workspace_id
            where membership.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int insert(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("userMemoryId") String userMemoryId,
            @Param("content") String content,
            @Param("sourceConversationId") String sourceConversationId,
            @Param("sourceAgentRunId") String sourceAgentRunId);

    /** 所有者确认：仅 PROPOSED 可确认，记录确认时间。 */
    @Update("""
            update user_memory
            set status = 'CONFIRMED', confirmed_at = current_timestamp
            where user_memory_id = #{userMemoryId}
              and workspace_id = #{workspaceId}
              and user_subject = #{userSubject}
              and status = 'PROPOSED'
            """)
    int confirm(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("userMemoryId") String userMemoryId);

    /** 所有者删除记忆；删除后不可再被使用。 */
    @Delete("""
            delete from user_memory
            where user_memory_id = #{userMemoryId}
              and workspace_id = #{workspaceId}
              and user_subject = #{userSubject}
            """)
    int delete(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("userMemoryId") String userMemoryId);

    /** 读取所有者在工作区的全部记忆（含待确认），按创建时间倒序。 */
    @Results(id = "userMemory", value = {
            @Result(column = "user_memory_id", property = "userMemoryId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "user_subject", property = "userSubject"),
            @Result(column = "source_conversation_id", property = "sourceConversationId"),
            @Result(column = "source_agent_run_id", property = "sourceAgentRunId"),
            @Result(column = "confirmed_at", property = "confirmedAt"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select memory.user_memory_id, memory.workspace_id, memory.user_subject, memory.content,
                   memory.status, memory.source_conversation_id, memory.source_agent_run_id,
                   memory.confirmed_at, memory.created_at
            from user_memory memory
            where memory.workspace_id = #{workspaceId}
              and memory.user_subject = #{userSubject}
            order by memory.created_at desc, memory.user_memory_id desc
            """)
    List<UserMemory> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId);

    /** Agent 上下文读取入口：只返回该工作区、该用户、已确认的记忆。 */
    @ResultMap("userMemory")
    @Select("""
            select memory.user_memory_id, memory.workspace_id, memory.user_subject, memory.content,
                   memory.status, memory.source_conversation_id, memory.source_agent_run_id,
                   memory.confirmed_at, memory.created_at
            from user_memory memory
            where memory.workspace_id = #{workspaceId}
              and memory.user_subject = #{userSubject}
              and memory.status = 'CONFIRMED'
            order by memory.created_at, memory.user_memory_id
            """)
    List<UserMemory> listConfirmed(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId);
}
