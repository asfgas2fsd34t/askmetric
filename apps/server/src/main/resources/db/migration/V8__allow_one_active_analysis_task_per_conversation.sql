DROP INDEX analysis_task_one_open_per_conversation_idx;

CREATE UNIQUE INDEX analysis_task_one_active_per_conversation_idx
    ON analysis_task (conversation_id)
    WHERE status = 'ACTIVE';
