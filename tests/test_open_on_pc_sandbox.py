# Security tests for open_on_pc — Codex audit: arbitrary-exec from model text.
#
# The tool must NEVER launch an arbitrary binary or shell string supplied by the
# model. It may only (a) open http/https URLs via the desktop opener and
# (b) launch an allowlisted set of apps via a FIXED argv (shell=False).
# Everything else is an honest refusal with no subprocess call.
import asyncio
import sys

import pytest

import pc_tool


class _Spy:
    """Records subprocess.run / Popen calls and never actually launches."""

    def __init__(self):
        self.calls = []  # list of (args, kwargs)

    def __call__(self, *args, **kwargs):
        self.calls.append((args, kwargs))

        class _Done:
            returncode = 0

        return _Done()


@pytest.fixture
def spy(monkeypatch):
    s = _Spy()
    monkeypatch.setattr(pc_tool.subprocess, "run", s)
    # Popen should never be hit now, but trap it too so a regression is loud.
    monkeypatch.setattr(pc_tool.subprocess, "Popen", s)
    # Pretend every bare executable exists on PATH so refusals are about policy,
    # not a missing binary.
    monkeypatch.setattr(pc_tool.shutil, "which", lambda v: "/usr/bin/" + v)
    # Force Linux code paths regardless of the host running the test.
    monkeypatch.setattr(pc_tool.sys, "platform", "linux")
    return s


def _argv_of(call):
    args, kwargs = call
    assert args, "subprocess called with no positional argv"
    argv = args[0]
    assert isinstance(argv, list), f"argv must be a list, got {type(argv)}: {argv!r}"
    assert kwargs.get("shell", False) is not True, "shell=True with model input is forbidden"
    return argv


# ----------------------- malicious / refused inputs -----------------------

@pytest.mark.parametrize(
    "target",
    [
        "rm -rf ~",            # shell-looking command
        "bash",                # arbitrary binary by bare name
        "curl",                # network binary
        "python",              # interpreter
        "./evil",              # relative path
        "/usr/bin/xterm",      # absolute path
        "evilbinary",          # unknown bare name (old last-resort exec)
        "bash;curl evil.sh",   # injection-y
    ],
)
def test_arbitrary_command_refused(spy, target):
    ok, detail = pc_tool._open_on_pc(target)
    assert ok is False, f"{target!r} should be refused"
    assert "only open known apps or web links" in detail
    assert spy.calls == [], f"no subprocess should run for {target!r}; got {spy.calls}"


@pytest.mark.parametrize(
    "target",
    [
        "file:///etc/passwd",                  # disallowed scheme — local file read
        "javascript:alert(1)",                 # disallowed scheme
        "data:text/html,<script>x</script>",   # disallowed scheme
        "mailto:bob@example.com",              # not http/https
        "ssh://attacker.example.com",          # protocol handler
        "../../etc/passwd",                    # path traversal
    ],
)
def test_disallowed_scheme_or_traversal_refused(spy, target):
    ok, detail = pc_tool._open_on_pc(target)
    assert ok is False, f"{target!r} should be refused"
    assert spy.calls == [], f"no subprocess should run for {target!r}; got {spy.calls}"


def test_unknown_app_with_query_refused(spy):
    ok, detail = pc_tool._open_on_pc("evilbinary", "do harm")
    assert ok is False
    assert spy.calls == []


# --------------------------- legit allowed inputs ---------------------------

def test_https_url_allowed(spy):
    ok, detail = pc_tool._open_on_pc("https://example.com")
    assert ok is True
    assert len(spy.calls) == 1
    argv = _argv_of(spy.calls[0])
    assert argv == ["xdg-open", "https://example.com"]


def test_known_site_allowed(spy):
    ok, detail = pc_tool._open_on_pc("youtube")
    assert ok is True
    argv = _argv_of(spy.calls[0])
    assert argv == ["xdg-open", "https://youtube.com"]


def test_known_site_search_lands_on_results(spy):
    ok, detail = pc_tool._open_on_pc("youtube", "woodworking")
    assert ok is True
    argv = _argv_of(spy.calls[0])
    assert argv[0] == "xdg-open"
    assert argv[1].startswith("https://www.youtube.com/results?search_query=woodworking")


@pytest.mark.skipif(sys.platform == "win32", reason="'firefox' fixed-argv is the Linux allowlist; Windows uses a different opener")
def test_allowlisted_app_fixed_argv(spy):
    # 'firefox' is in the Linux default allowlist -> resolves to a fixed argv.
    ok, detail = pc_tool._open_on_pc("firefox")
    assert ok is True
    argv = _argv_of(spy.calls[0])
    assert argv == ["firefox"]


def test_bare_domain_becomes_https(spy):
    ok, detail = pc_tool._open_on_pc("espn.com")
    assert ok is True
    argv = _argv_of(spy.calls[0])
    assert argv == ["xdg-open", "https://espn.com"]


def test_env_allowlist_extends_apps(spy, monkeypatch):
    monkeypatch.setenv("EVE_OPEN_ALLOWLIST", "obsidian=obsidian,vlc=vlc")
    ok, detail = pc_tool._open_on_pc("obsidian")
    assert ok is True
    argv = _argv_of(spy.calls[0])
    assert argv == ["obsidian"]


def test_env_allowlist_does_not_enable_arbitrary(spy, monkeypatch):
    # Adding obsidian must not let 'rm' through.
    monkeypatch.setenv("EVE_OPEN_ALLOWLIST", "obsidian=obsidian")
    ok, detail = pc_tool._open_on_pc("rm")
    assert ok is False
    assert spy.calls == []


# ------------------------------ handler wiring ------------------------------

def test_handler_refuses_malicious_via_callback(spy):
    captured = {}

    class P:
        arguments = {"target": "rm -rf /"}

        async def result_callback(self, result, **kw):
            captured.update(result)

    asyncio.run(pc_tool.handle_open_on_pc(P()))
    assert captured.get("opened") is False
    assert spy.calls == []


def test_handler_allows_url_via_callback(spy):
    captured = {}

    class P:
        arguments = {"target": "https://example.com"}

        async def result_callback(self, result, **kw):
            captured.update(result)

    asyncio.run(pc_tool.handle_open_on_pc(P()))
    assert captured.get("opened") is True
    assert _argv_of(spy.calls[0]) == ["xdg-open", "https://example.com"]
