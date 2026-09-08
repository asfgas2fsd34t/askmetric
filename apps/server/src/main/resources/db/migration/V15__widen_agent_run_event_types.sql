ALTER TABLE agent_run_event
    DROP CONSTRAINT agent_run_event_event_type_check;

ALTER TABLE agent_run_event
    ADD CONSTRAINT agent_run_event_event_type_check
        CHECK (event_type IN (
            'ACCEPTED', 'PROGRESS', 'CLARIFICATION', 'PLAN',
            'COMPLETED', 'FAILED', 'CANCELLED'));
