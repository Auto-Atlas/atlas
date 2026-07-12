"""Fail-fast check that the a2a-sdk API the fabric relies on is present with the shape we
introspected (2026-06-30 base + 2026-07-01 talk-back additions). Run in CI before the fabric
tests so an SDK upgrade that moves a symbol fails loudly here instead of deep inside
a2a_fabric. Exits non-zero on any mismatch."""


def verify() -> bool:
    from a2a.server.agent_execution import AgentExecutor, RequestContext  # noqa: F401
    from a2a.server.events import EventQueue  # noqa: F401
    from a2a.server.tasks import (  # noqa: F401
        TaskUpdater,
        InMemoryTaskStore,
        InMemoryPushNotificationConfigStore,
        BasePushNotificationSender,
    )
    from a2a.server.request_handlers import DefaultRequestHandler  # noqa: F401
    from a2a.server.routes import (  # noqa: F401
        add_a2a_routes_to_fastapi,
        create_agent_card_routes,
        create_jsonrpc_routes,
    )
    from a2a.client import ClientFactory, ClientConfig, minimal_agent_card  # noqa: F401
    from a2a.helpers import new_task_from_user_message  # noqa: F401
    from a2a.utils import AGENT_CARD_WELL_KNOWN_PATH, DEFAULT_RPC_URL  # noqa: F401
    from a2a.utils import to_stream_response  # noqa: F401
    from a2a.types import a2a_pb2 as pb

    for m in ("start_work", "complete", "failed", "requires_input", "new_agent_message"):
        assert hasattr(TaskUpdater, m), f"TaskUpdater missing {m}"
    for s in ("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_INPUT_REQUIRED",
              "TASK_STATE_WORKING", "TASK_STATE_SUBMITTED"):
        assert s in pb.TaskState.keys(), f"TaskState missing {s}"
    assert hasattr(pb, "Part") and hasattr(pb, "Message"), "proto Part/Message missing"
    # Talk-back additions (2026-07-01):
    assert AGENT_CARD_WELL_KNOWN_PATH == "/.well-known/agent-card.json"
    assert hasattr(pb.SendMessageConfiguration(), "return_immediately"), \
        "SendMessageConfiguration.return_immediately missing"
    assert hasattr(pb, "AgentCard") and hasattr(pb, "AgentInterface") and \
        hasattr(pb, "AgentCapabilities") and hasattr(pb, "AgentSkill"), "AgentCard protos missing"
    import inspect as _i
    from a2a.client import create_client
    assert _i.iscoroutinefunction(create_client), "create_client must be async"
    return True


if __name__ == "__main__":
    verify()
    print("a2a-sdk API verified")
