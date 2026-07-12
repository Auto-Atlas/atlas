#
# open_on_pc — launch apps and websites on the PC (Windows or Linux desktop),
# optionally landing directly on in-site search results. Shared by the
# desktop voice loop and the phone loop (saying "open YouTube" into your
# earbuds opens it on the PC).
#

import os
import shutil
import subprocess
import sys

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

if sys.platform == "win32":
    _PC_APPS = {
        "notepad": "notepad.exe",
        "calculator": "calc.exe",
        "calc": "calc.exe",
        "paint": "mspaint.exe",
        "file explorer": "explorer.exe",
        "explorer": "explorer.exe",
        "files": "explorer.exe",
        "task manager": "taskmgr.exe",
        "cmd": "cmd.exe",
        "command prompt": "cmd.exe",
        "terminal": "wt.exe",
        "settings": "ms-settings:",
        "spotify": "spotify:",
        "camera": "microsoft.windows.camera:",
    }
else:
    # Linux desktop: bare executable names, resolved on PATH at launch time.
    _PC_APPS = {
        "notepad": "gedit",
        "text editor": "gedit",
        "calculator": "gnome-calculator",
        "calc": "gnome-calculator",
        "file explorer": "nautilus",
        "explorer": "nautilus",
        "files": "nautilus",
        "task manager": "gnome-system-monitor",
        "terminal": "gnome-terminal",
        "settings": "gnome-control-center",
        "spotify": "spotify",
        "chrome": "google-chrome",
        "google chrome": "google-chrome",
        "firefox": "firefox",
        "brave": "brave-browser",
        "browser": "google-chrome",
        "web browser": "google-chrome",
    }

# Browser executables that can take a URL argument: "open chrome and search
# for X" hands the browser a results URL instead of failing on the app name.
_PC_BROWSERS = {"chrome", "google chrome", "firefox", "brave", "browser", "web browser"}
_PC_SITES = {
    "youtube": "https://youtube.com",
    "google": "https://google.com",
    "gmail": "https://mail.google.com",
    "google calendar": "https://calendar.google.com",
    "calendar": "https://calendar.google.com",
    "maps": "https://maps.google.com",
    "github": "https://github.com",
    "chatgpt": "https://chat.openai.com",
    "reddit": "https://reddit.com",
    "amazon": "https://amazon.com",
    "netflix": "https://netflix.com",
}

# Site-specific search URL templates: {q} is the url-encoded query. When the
# user says "open YouTube and search for X", we land DIRECTLY on the results
# page instead of the homepage (which looked like a search that never happened).
_PC_SEARCH = {
    "youtube": "https://www.youtube.com/results?search_query={q}",
    "google": "https://www.google.com/search?q={q}",
    "maps": "https://www.google.com/maps/search/{q}",
    "google maps": "https://www.google.com/maps/search/{q}",
    "amazon": "https://www.amazon.com/s?k={q}",
    "github": "https://github.com/search?q={q}",
    "reddit": "https://www.reddit.com/search/?q={q}",
    "gmail": "https://mail.google.com/mail/u/0/#search/{q}",
    "netflix": "https://www.netflix.com/search?q={q}",
}

# Honest refusal returned whenever a request doesn't map to a known app or a
# safe web link. This is the security boundary: model text never becomes a
# command or an arbitrary binary launch.
_REFUSAL = "I can only open known apps or web links on the PC"


def _app_allowlist() -> dict[str, str]:
    """Permitted app-name -> launch-value map, env-extensible.

    EVE_OPEN_ALLOWLIST is a comma-separated list of "name=command" pairs, e.g.
    "obsidian=obsidian,vlc=vlc". Lets the user add apps without code changes,
    while keeping launches confined to a fixed, vetted mapping (no arbitrary
    model text ever reaches an exec).
    """
    apps = dict(_PC_APPS)
    raw = os.environ.get("EVE_OPEN_ALLOWLIST", "")
    for entry in raw.split(","):
        entry = entry.strip()
        if not entry or "=" not in entry:
            continue
        name, _, cmd = entry.partition("=")
        name, cmd = name.strip().lower(), cmd.strip()
        if name and cmd:
            apps[name] = cmd
    return apps


def _is_safe_web_url(value: str) -> bool:
    """True only for http/https URLs. Rejects file:, javascript:, data:,
    mailto:, and any other scheme that could trigger a handler or read disk."""
    return value.startswith(("http://", "https://"))


def _launch_url(url: str) -> None:
    """Open a validated http/https URL via the desktop opener (no shell)."""
    if not _is_safe_web_url(url):
        raise ValueError(f"refused unsafe url: {url!r}")
    if sys.platform == "win32":
        os.startfile(url)  # type: ignore[attr-defined]  # Windows-only
        return
    subprocess.run(
        ["xdg-open", url],
        shell=False,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _launch_app(value: str) -> None:
    """Launch an allowlisted app by its fixed mapped value (no shell).

    `value` is the right-hand side of an allowlist entry (e.g. "google-chrome",
    "notepad.exe", "spotify:" / "ms-settings:"), NEVER raw model text.
    """
    if sys.platform == "win32":
        if value.lower().endswith(".exe"):
            subprocess.run([value], shell=False, check=False)
        else:
            os.startfile(value)  # type: ignore[attr-defined]  # protocol / URI
        return
    # Linux: protocol/URI values (e.g. "spotify:") go through xdg-open; bare
    # executable names are resolved on PATH and run directly. No shell.
    if "/" not in value and ":" in value and value.split(":", 1)[0].isalnum():
        subprocess.run(
            ["xdg-open", value],
            shell=False,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return
    if shutil.which(value) is None:
        raise FileNotFoundError(value)
    subprocess.run(
        [value],
        shell=False,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _looks_like_bare_domain(t: str) -> bool:
    """Heuristic for a plain hostname like 'espn.com' (no scheme, no spaces).

    Conservative: a single dotted token whose labels are alnum/hyphen and whose
    last label (TLD) is alphabetic. This rejects exec-y inputs like
    'rm -rf', './evil', 'bash;curl', or paths, so they fall through to refusal
    instead of being turned into a URL or a command.
    """
    if not t or " " in t or "/" in t or ":" in t or "." not in t:
        return False
    labels = t.split(".")
    if len(labels) < 2 or not all(labels):
        return False
    if not all(all(c.isalnum() or c == "-" for c in lab) for lab in labels):
        return False
    return labels[-1].isalpha() and len(labels[-1]) >= 2


def _open_on_pc(target: str, query: str = "") -> tuple[bool, str]:
    t = (target or "").strip().lower()
    q = (query or "").strip()
    if not t and q:
        t = "google"  # bare search -> Google results
    if not t:
        return False, "nothing was specified to open"
    apps = _app_allowlist()
    try:
        if q:
            from urllib.parse import quote_plus

            template = _PC_SEARCH.get(t)
            if template:
                url = template.format(q=quote_plus(q))
                _launch_url(url)
                return True, f"opened {t} search results for '{q}'"
            if sys.platform != "win32" and t in _PC_BROWSERS and t in apps:
                # Hand the allowlisted browser a fixed Google results URL.
                url = f"https://www.google.com/search?q={quote_plus(q)}"
                subprocess.run(
                    [apps[t], url],
                    shell=False,
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                return True, f"opened {t} with search results for '{q}'"
            if t in _PC_SITES or _looks_like_bare_domain(t):
                # No search template for this site — be honest about it.
                _launch_url(_PC_SITES.get(t, f"https://{t}"))
                return True, (
                    f"opened {t}, but searching within it isn't supported — "
                    f"tell the user you opened the site without running the search"
                )
            return False, _REFUSAL
        if t.startswith(("http://", "https://")):
            if not _is_safe_web_url(t):
                return False, _REFUSAL
            _launch_url(t)
            return True, f"opened {t}"
        if "://" in t:  # some other scheme (file:, javascript:, ssh:, ...)
            return False, _REFUSAL
        if t in _PC_SITES:
            _launch_url(_PC_SITES[t])
            return True, f"opened {t}"
        if t in apps:
            _launch_app(apps[t])
            return True, f"opened {t}"
        if _looks_like_bare_domain(t):  # e.g. "espn.com"
            url = "https://" + t
            _launch_url(url)
            return True, f"opened {url}"
        # No arbitrary-exec fallback: anything unrecognised is refused, never
        # launched as a binary or shell command.
        return False, _REFUSAL
    except (OSError, FileNotFoundError, ValueError):
        return False, f"could not find or open '{target}'"


OPEN_ON_PC_SCHEMA = FunctionSchema(
    name="open_on_pc",
    description=(
        "Open an application or website on the user's PC, optionally landing on "
        "search results within it. Use for 'open Notepad', 'launch Spotify', 'open YouTube "
        "and search for woodworking', or 'google best pizza in Worcester'. Sites with "
        "search support: youtube, google, maps, amazon, github, reddit, gmail, netflix."
    ),
    properties={
        "target": {
            "type": "string",
            "description": "The app or website to open, e.g. 'notepad', 'spotify', 'youtube', 'github.com'.",
        },
        "query": {
            "type": "string",
            "description": "Optional search to run on the target site, e.g. 'woodworking videos'. Empty for none.",
        },
    },
    required=["target"],
)


async def handle_open_on_pc(params: FunctionCallParams):
    target = str(params.arguments.get("target", ""))
    query = str(params.arguments.get("query", "") or "")
    ok, detail = _open_on_pc(target, query)
    logger.info(f"open_on_pc(target={target!r}, query={query!r}) -> ok={ok} ({detail})")
    await params.result_callback({"opened": ok, "detail": detail})
