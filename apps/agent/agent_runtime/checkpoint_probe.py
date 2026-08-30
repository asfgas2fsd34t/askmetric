import argparse
import json
import os
from typing import TypedDict, cast

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import END, START, StateGraph


class ProbeEvent(TypedDict):
    event_type: str
    sequence: int
    conversation_id: str
    run_id: str
    correlation_id: str


class ProbeState(TypedDict):
    conversation_id: str
    run_id: str
    correlation_id: str
    events: list[ProbeEvent]


class ProbeExecution(TypedDict):
    state: ProbeState
    emitted_events: list[ProbeEvent]


def recorded_event(state: ProbeState, event_type: str, sequence: int) -> ProbeEvent:
    return {
        "event_type": event_type,
        "sequence": sequence,
        "conversation_id": state["conversation_id"],
        "run_id": state["run_id"],
        "correlation_id": state["correlation_id"],
    }


def record_progress(state: ProbeState) -> dict[str, list[ProbeEvent]]:
    return {"events": [*state["events"], recorded_event(state, "agent.run.progress", 2)]}


def record_completion(state: ProbeState) -> dict[str, list[ProbeEvent]]:
    return {"events": [*state["events"], recorded_event(state, "agent.run.completed", 3)]}


def build_graph(checkpointer: BaseCheckpointSaver):
    builder = StateGraph(ProbeState)
    builder.add_node("record_progress", record_progress)
    builder.add_node("record_completion", record_completion)
    builder.add_edge(START, "record_progress")
    builder.add_edge("record_progress", "record_completion")
    builder.add_edge("record_completion", END)
    return builder.compile(checkpointer=checkpointer, interrupt_after=["record_progress"])


def start_run(
    checkpointer: BaseCheckpointSaver,
    *,
    conversation_id: str,
    run_id: str,
    correlation_id: str,
) -> ProbeExecution:
    state = cast(ProbeState, build_graph(checkpointer).invoke(
        {
            "conversation_id": conversation_id,
            "run_id": run_id,
            "correlation_id": correlation_id,
            "events": [],
        },
        {"configurable": {"thread_id": run_id}},
    ))
    return {"state": state, "emitted_events": state["events"]}


def resume_run(checkpointer: BaseCheckpointSaver, *, run_id: str) -> ProbeExecution:
    graph = build_graph(checkpointer)
    config: RunnableConfig = {"configurable": {"thread_id": run_id}}
    before = cast(ProbeState, graph.get_state(config).values)
    state = cast(ProbeState, graph.invoke(
        None,
        config,
    ))
    return {
        "state": state,
        "emitted_events": state["events"][len(before.get("events", [])):],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe LangGraph PostgreSQL Checkpoint recovery")
    parser.add_argument("command", choices=("start", "resume"))
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--conversation-id")
    parser.add_argument("--correlation-id")
    args = parser.parse_args()

    database_url = os.environ["CHECKPOINT_DATABASE_URL"]
    with PostgresSaver.from_conn_string(database_url) as checkpointer:
        checkpointer.setup()
        if args.command == "start":
            if not args.conversation_id or not args.correlation_id:
                parser.error("start requires --conversation-id and --correlation-id")
            result = start_run(
                checkpointer,
                conversation_id=args.conversation_id,
                run_id=args.run_id,
                correlation_id=args.correlation_id,
            )
        else:
            result = resume_run(checkpointer, run_id=args.run_id)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
