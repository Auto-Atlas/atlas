# Tests for jarvis_core.trim_messages — the pure context-window trim that keeps
# the protected head (system prompt + persona + memory pack + primed skills)
# alive while dropping the oldest non-protected middle messages on a long call.
from jarvis_core import trim_messages


def _msgs(n, start=0):
    """n plain user/assistant messages, content tagged so we can assert order."""
    return [{"role": "user", "content": f"m{start + i}"} for i in range(n)]


def test_under_cap_unchanged():
    head = [{"role": "system", "content": "PROMPT"}]
    msgs = head + _msgs(5)
    out = trim_messages(msgs, protected_head_len=1, max_msgs=50, keep=30)
    assert out == msgs
    assert out is not msgs  # returns a copy, never mutates the input


def test_over_cap_preserves_head_keeps_newest_drops_oldest_middle():
    head = [
        {"role": "system", "content": "PROMPT"},
        {"role": "system", "content": "MEMORY"},
        {"role": "system", "content": "SKILL"},
    ]
    body = _msgs(40)  # m0..m39
    msgs = head + body
    out = trim_messages(msgs, protected_head_len=3, max_msgs=10, keep=5)

    # Protected head fully preserved, in order, at the front.
    assert out[:3] == head
    # Newest `keep` body messages kept, in order; oldest middle dropped.
    assert out[3:] == body[-5:]
    assert [m["content"] for m in out[3:]] == ["m35", "m36", "m37", "m38", "m39"]
    # Result fits the budget (head + keep).
    assert len(out) <= 3 + 5


def test_head_longer_than_cap_never_drops_head():
    head = [{"role": "system", "content": f"h{i}"} for i in range(20)]
    msgs = head + _msgs(5)
    # max_msgs smaller than the head itself: the head must STILL survive whole.
    out = trim_messages(msgs, protected_head_len=20, max_msgs=10, keep=3)
    assert out[:20] == head
    # keep clamps to the body only — never reaches back into the head.
    assert out[20:] == msgs[-3:]


def test_protected_head_len_ge_len_unchanged():
    msgs = [{"role": "system", "content": "PROMPT"}] + _msgs(5)
    out = trim_messages(msgs, protected_head_len=len(msgs), max_msgs=3, keep=2)
    assert out == msgs


def test_tail_never_leads_with_orphaned_tool_result():
    head = [{"role": "system", "content": "PROMPT"}]
    body = (
        _msgs(38)
        + [{"role": "tool", "content": "TOOL_RESULT"}]
        + [{"role": "assistant", "content": "after-tool"}]
    )
    msgs = head + body
    # keep window would start on the tool result; it must be stripped so the
    # tail doesn't dangle without its preceding assistant tool call.
    out = trim_messages(msgs, protected_head_len=1, max_msgs=10, keep=2)
    assert out[0] == head[0]
    assert out[1]["role"] != "tool"
    assert out[-1] == {"role": "assistant", "content": "after-tool"}


def test_ordering_preserved_head_then_recent():
    head = [{"role": "system", "content": "PROMPT"}]
    body = _msgs(60)
    msgs = head + body
    out = trim_messages(msgs, protected_head_len=1, max_msgs=20, keep=10)
    contents = [m["content"] for m in out]
    assert contents[0] == "PROMPT"
    # Strictly increasing tail (no reordering): the newest 10 in order.
    assert contents[1:] == [f"m{i}" for i in range(50, 60)]


def test_keep_zero_yields_head_only():
    head = [{"role": "system", "content": "PROMPT"}]
    msgs = head + _msgs(40)
    out = trim_messages(msgs, protected_head_len=1, max_msgs=10, keep=0)
    assert out == head
