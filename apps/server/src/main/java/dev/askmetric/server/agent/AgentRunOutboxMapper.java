package dev.askmetric.server.agent;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Agent Run Outbox 的持久化查询和租约操作。 */
@Mapper
public interface AgentRunOutboxMapper {
    /** 在 Agent Run 事务中插入待发布请求；同一事件 ID 只保留一条记录。 */
    @Insert("""
            insert into agent_run_outbox (outbox_id, event_id, run_id, topic, payload, status)
            values (#{outboxId}, #{eventId}, #{runId}, #{topic}, #{payload}, 'PENDING')
            on conflict (event_id) do nothing
            """)
    int enqueue(
            @Param("outboxId") String outboxId,
            @Param("eventId") String eventId,
            @Param("runId") String runId,
            @Param("topic") String topic,
            @Param("payload") String payload);

    /** 使用数据库行锁和短租约领取待发布记录，避免多实例重复并发发布。 */
    @Results(id = "agentRunOutbox", value = {
            @Result(column = "outbox_id", property = "outboxId"),
            @Result(column = "event_id", property = "eventId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "topic", property = "topic"),
            @Result(column = "payload", property = "payload"),
            @Result(column = "status", property = "status"),
            @Result(column = "attempts", property = "attempts"),
            @Result(column = "next_attempt_at", property = "nextAttemptAt"),
            @Result(column = "lease_until", property = "leaseUntil"),
            @Result(column = "last_error", property = "lastError"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "published_at", property = "publishedAt")
    })
    @Select("""
            with candidates as (
                select outbox_id
                from agent_run_outbox
                where (status = 'PENDING' and next_attempt_at <= current_timestamp)
                   or (status = 'PUBLISHING' and lease_until < current_timestamp)
                order by created_at, outbox_id
                for update skip locked
                limit #{limit}
            )
            update agent_run_outbox outbox
            set status = 'PUBLISHING',
                attempts = outbox.attempts + 1,
                lease_until = current_timestamp + interval '30 seconds'
            from candidates
            where outbox.outbox_id = candidates.outbox_id
            returning outbox.outbox_id, outbox.event_id, outbox.run_id, outbox.topic,
                      outbox.payload, outbox.status, outbox.attempts, outbox.next_attempt_at,
                      outbox.lease_until, outbox.last_error, outbox.created_at, outbox.published_at
            """)
    List<AgentRunOutbox> claim(@Param("limit") int limit);

    /** 标记发布成功。 */
    @Update("""
            update agent_run_outbox
            set status = 'PUBLISHED', lease_until = null, published_at = current_timestamp
            where outbox_id = #{outboxId}
              and status = 'PUBLISHING'
            """)
    int markPublished(@Param("outboxId") String outboxId);

    /** 记录失败并安排有限次数的指数退避重试，超过上限后进入死信状态。 */
    @Update("""
            update agent_run_outbox
            set status = case when attempts >= #{maxAttempts} then 'DEAD_LETTER' else 'PENDING' end,
                next_attempt_at = current_timestamp + make_interval(secs => #{delaySeconds}),
                lease_until = null,
                last_error = #{error}
            where outbox_id = #{outboxId}
              and status = 'PUBLISHING'
            """)
    int markFailure(
            @Param("outboxId") String outboxId,
            @Param("maxAttempts") int maxAttempts,
            @Param("delaySeconds") int delaySeconds,
            @Param("error") String error);
}
