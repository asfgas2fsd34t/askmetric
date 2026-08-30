from agent_runtime.checkpoint_probe import resume_run, start_run
from langgraph.checkpoint.memory import InMemorySaver


def test_agent_run_resumes_from_checkpoint_without_repeating_recorded_events():
    checkpointer = InMemorySaver()
    progress = {
        "event_type": "agent.run.progress",
        "sequence": 2,
        "conversation_id": "conversation-1",
        "run_id": "run-1",
        "correlation_id": "correlation-1",
    }
    completed = {
        "event_type": "agent.run.completed",
        "sequence": 3,
        "conversation_id": "conversation-1",
        "run_id": "run-1",
        "correlation_id": "correlation-1",
    }

    interrupted = start_run(
        checkpointer,
        conversation_id="conversation-1",
        run_id="run-1",
        correlation_id="correlation-1",
    )

    assert interrupted == {
        "state": {
            "conversation_id": "conversation-1",
            "run_id": "run-1",
            "correlation_id": "correlation-1",
            "events": [progress],
        },
        "emitted_events": [progress],
    }

    resumed = resume_run(checkpointer, run_id="run-1")
    resumed_again = resume_run(checkpointer, run_id="run-1")

    assert resumed == {
        "state": {
            "conversation_id": "conversation-1",
            "run_id": "run-1",
            "correlation_id": "correlation-1",
            "events": [progress, completed],
        },
        "emitted_events": [completed],
    }
    assert resumed_again == {"state": resumed["state"], "emitted_events": []}
