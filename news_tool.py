#
# News briefing — real headlines from Google News RSS, no API key. Top
# stories by default, or a topic search. Only what the feed returns is
# reported; a fetch failure says so instead of inventing news.
#

import xml.etree.ElementTree as ET
from urllib.parse import quote_plus

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

_TIMEOUT = aiohttp.ClientTimeout(total=8)
_BASE = "https://news.google.com/rss"

GET_NEWS_SCHEMA = FunctionSchema(
    name="get_news",
    description=(
        "Real current news headlines (Google News). Use for 'what's in the news', "
        "'any news about X', or a morning briefing. Returns the top stories with sources."
    ),
    properties={
        "topic": {"type": "string", "description": "Optional topic, e.g. 'AI', 'Worcester', 'Patriots'. Empty for top stories."}
    },
    required=[],
)


async def fetch_news(topic: str = "", limit: int = 5) -> list[dict]:
    url = f"{_BASE}/search?q={quote_plus(topic)}&hl=en-US&gl=US&ceid=US:en" if topic.strip() else f"{_BASE}?hl=en-US&gl=US&ceid=US:en"
    async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
        async with session.get(url) as resp:
            if resp.status != 200:
                raise RuntimeError(f"news feed returned HTTP {resp.status}")
            body = await resp.text()
    items = []
    for item in ET.fromstring(body).iter("item"):
        title = (item.findtext("title") or "").strip()
        source = (item.findtext("source") or "").strip()
        if title:
            items.append({"headline": title, "source": source})
        if len(items) >= limit:
            break
    return items


async def handle_get_news(params: FunctionCallParams):
    topic = str(params.arguments.get("topic", "") or "")
    try:
        items = await fetch_news(topic)
    except Exception as e:
        await params.result_callback({"ok": False, "error": f"news lookup failed: {e}"})
        return
    logger.info(f"get_news(topic={topic!r}) -> {len(items)} headlines")
    await params.result_callback(
        {
            "ok": True,
            "topic": topic or "top stories",
            "headlines": items,
            "instruction": "Pick the 3-4 most interesting and speak them as one natural brief; name the source for each.",
        }
    )
