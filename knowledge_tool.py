#
# Knowledge — semantically search the user's OpenJarvis knowledge base (past
# indexed content, business docs, prior context) via the local daemon. READ
# ONLY: it surfaces what's already stored, nothing leaves the machine, so it's
# ungated.
#
# This is DISTINCT from remember/recall, which is the personal wiki. Knowledge
# search is the broader semantic index over indexed content. The descriptions
# say so on both sides so the model picks the right tool.
#

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from openjarvis_client import OpenJarvisClient

# Cap each result so one long doc can't flood the voice context window.
_CONTENT_MAX_CHARS = 200

SEARCH_KNOWLEDGE_SCHEMA = FunctionSchema(
    name="search_knowledge",
    description=(
        "Semantically search the user's OpenJarvis knowledge base — past indexed "
        "content, business documents, and prior context. Use for questions like "
        "'what do I know about X', looking up a business doc, or surfacing earlier "
        "context. This is DISTINCT from recall/remember, which is the personal "
        "wiki; use search_knowledge for the broader indexed knowledge base."
    ),
    properties={
        "query": {
            "type": "string",
            "description": "What to search the knowledge base for, in natural language.",
        },
    },
    required=["query"],
)


async def handle_search_knowledge(params: FunctionCallParams):
    query = str(params.arguments.get("query", "")).strip()
    if not query:
        await params.result_callback(
            {"ok": False, "error": "a search query is required"}
        )
        return

    try:
        results = await OpenJarvisClient().memory_search(query, top_k=5)
    except Exception as e:
        logger.warning(f"search_knowledge failed: {e}")
        await params.result_callback(
            {"ok": False, "error": f"knowledge search failed: {e}"}
        )
        return

    trimmed = [
        {
            "content": str(r.get("content", ""))[:_CONTENT_MAX_CHARS],
            "score": r.get("score"),
        }
        for r in results
    ]
    logger.info(f"search_knowledge {query!r} -> {len(trimmed)} results")
    await params.result_callback(
        {
            "ok": True,
            "results": trimmed,
            "instruction": (
                "Summarize the relevant results out loud in a sentence or two; "
                "if empty, say nothing was found."
            ),
        }
    )
