package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Events pushed over WS /v1/stream.
 *
 * Two families ride this one socket:
 *  - **Approvals** — `approval_resolved` (with ok? denied? tool?), `approval_pending`,
 *    `approval_expired`. The original payload; `type` discriminates.
 *  - **Live tool-call / delegation feed** — `tool_call` / `tool_result` and
 *    `delegation_start` / `delegation_step` / `delegation_end` / `thinking`, republished by
 *    `approval_api` from the voice loop's transcript so the app can render the same
 *    transformative tool-call UI the desktop + phone-web NeuralBrain show. These are produced by
 *    `bridge.py` (see its header for the wire shapes) and `agent_bridge.py`.
 *
 * One tolerant model decodes everything: every field is optional, and the decoder is configured
 * with `ignoreUnknownKeys` (ApiClient.DEFAULT_JSON), so unmodeled fields (`ts`, `src`, `tokens`,
 * future event types) decode without crashing the live connection.
 */
@Serializable
data class StreamEvent(
    val type: String,
    // ---- Approvals ----
    val id: String? = null,
    val ok: Boolean? = null,
    val denied: Boolean? = null,
    val tool: String? = null,
    // ---- Live tool-call / delegation feed ----
    /** Delegation correlation id (delegation_start/step/end). */
    @SerialName("deleg_id") val delegId: String? = null,
    /** The delegated task text (delegation_start). */
    val task: String? = null,
    /** The brain chain to be tried, in order (delegation_start), e.g. ["codex","glm","local"]. */
    val brains: List<String>? = null,
    /** Which brain a step concerns (delegation_step/end). */
    val brain: String? = null,
    /** Step phase: "try" | "working" | "answer" | "fail" (delegation_step). */
    val phase: String? = null,
    /** Step detail / tool result preview ("16s", a clipped result, …). */
    val detail: String? = null,
    /** tool_call argument preview (string). */
    val args: String? = null,
    /** tool_call lifecycle hint, currently "running". */
    val status: String? = null,
    /** delegation_end full (untruncated) result. */
    val result: String? = null,
    /** delegation_end accumulated failure reasons. */
    val failures: List<String>? = null,
    /** Per-step latency (delegation_step). */
    @SerialName("latency_ms") val latencyMs: Long? = null,
    /** Whole-delegation latency (delegation_end). */
    @SerialName("total_latency_ms") val totalLatencyMs: Long? = null,
    /** thinking on/off. */
    val active: Boolean? = null,
    // ---- Phone camera vision (look_via_phone) ----
    /** capture_frame correlation id — plain lowercase hex, 8-32 chars (see vision_frames.valid_id). */
    @SerialName("request_id") val requestId: String? = null,
    /** What EVE wants to see (may be empty); shown to the user in the "EVE is looking…" indicator. */
    val prompt: String? = null,
    /**
     * Capture source hint on capture_frame: "any" | "phone" | "glasses". Missing/unknown → treated
     * as "any" by [app.eve.vision.CaptureSource.fromWire]. The phone path ignores "glasses" events;
     * "any" prefers the Meta glasses when they're connected + enabled, else the phone.
     */
    val source: String? = null,
    // ---- Surfaced visuals (surface_visual) — EVE SHOWS something on the phone ----
    /** Visual kind: "desktop_screen" | "image" | "note" (see visual_tool.py). */
    val kind: String? = null,
    /** Short card title (server pre-fills a default; may still be empty). */
    val title: String? = null,
    /** Image id to fetch from GET /v1/visual/{id} — plain lowercase hex; empty for notes. */
    @SerialName("visual_id") val visualId: String? = null,
    /** Fetch path "/v1/visual/{id}" (empty for notes) — informational; the app builds its own. */
    val url: String? = null,
    /** Note/log text to show (kind=note); empty for image kinds. */
    val text: String? = null,
    // ---- Agent talk-back lifecycle (live-delegation-approvals) ----
    /** Stable agent-task id (agent_* events) — the key for per-task activity cards. */
    @SerialName("task_id") val taskId: String? = null,
    /** Which delegated agent this task belongs to (hermes/claude/codex). */
    val agent: String? = null,
    /** Short task summary (agent_* events). */
    val summary: String? = null,
) {
    val isResolved: Boolean get() = type == TYPE_RESOLVED
    val isPending: Boolean get() = type == TYPE_PENDING
    val isExpired: Boolean get() = type == TYPE_EXPIRED

    val isToolCall: Boolean get() = type == TYPE_TOOL_CALL
    val isToolResult: Boolean get() = type == TYPE_TOOL_RESULT
    val isDelegationStart: Boolean get() = type == TYPE_DELEGATION_START
    val isDelegationStep: Boolean get() = type == TYPE_DELEGATION_STEP
    val isDelegationEnd: Boolean get() = type == TYPE_DELEGATION_END
    val isThinking: Boolean get() = type == TYPE_THINKING

    val isCaptureFrame: Boolean get() = type == TYPE_CAPTURE_FRAME

    val isSurfaceVisual: Boolean get() = type == TYPE_SURFACE_VISUAL

    val isAgentProgress: Boolean get() = type == TYPE_AGENT_PROGRESS
    val isAgentQuestion: Boolean get() = type == TYPE_AGENT_QUESTION
    val isAgentResult: Boolean get() = type == TYPE_AGENT_RESULT
    val isAgentBlocker: Boolean get() = type == TYPE_AGENT_BLOCKER
    val isAgentTaskAssigned: Boolean get() = type == TYPE_AGENT_TASK_ASSIGNED
    val isAgentTaskCancelled: Boolean get() = type == TYPE_AGENT_TASK_CANCELLED
    val isAgentTaskRedirected: Boolean get() = type == TYPE_AGENT_TASK_REDIRECTED

    /** Any agent talk-back lifecycle event — the Approvals live-activity feed folds these. */
    val isAgentTaskEvent: Boolean get() = type in AGENT_TASK_TYPES

    companion object {
        const val TYPE_RESOLVED = "approval_resolved"
        const val TYPE_PENDING = "approval_pending"
        const val TYPE_EXPIRED = "approval_expired"

        const val TYPE_TOOL_CALL = "tool_call"
        const val TYPE_TOOL_RESULT = "tool_result"
        const val TYPE_DELEGATION_START = "delegation_start"
        const val TYPE_DELEGATION_STEP = "delegation_step"
        const val TYPE_DELEGATION_END = "delegation_end"
        const val TYPE_THINKING = "thinking"

        // Phone camera vision: the server asks the app to snap one frame.
        const val TYPE_CAPTURE_FRAME = "capture_frame"

        // Surfaced visual: EVE pushes a card (screenshot / image / note) for the app to SHOW.
        const val TYPE_SURFACE_VISUAL = "surface_visual"

        // Agent talk-back lifecycle: a delegated agent (Hermes/Claude/Codex) working a real
        // multi-minute task via the a2a fabric. Emitted by agent_delivery/_emit_assigned/
        // cancel+redirect paths, forwarded src-independently by approval_api.
        const val TYPE_AGENT_PROGRESS = "agent_progress"
        const val TYPE_AGENT_QUESTION = "agent_question"
        const val TYPE_AGENT_RESULT = "agent_result"
        const val TYPE_AGENT_BLOCKER = "agent_blocker"
        const val TYPE_AGENT_TASK_ASSIGNED = "agent_task_assigned"
        const val TYPE_AGENT_TASK_CANCELLED = "agent_task_cancelled"
        const val TYPE_AGENT_TASK_REDIRECTED = "agent_task_redirected"

        val AGENT_TASK_TYPES = setOf(
            TYPE_AGENT_PROGRESS, TYPE_AGENT_QUESTION, TYPE_AGENT_RESULT, TYPE_AGENT_BLOCKER,
            TYPE_AGENT_TASK_ASSIGNED, TYPE_AGENT_TASK_CANCELLED, TYPE_AGENT_TASK_REDIRECTED,
        )

        // Phases on delegation_step.
        const val PHASE_TRY = "try"
        const val PHASE_WORKING = "working"
        const val PHASE_ANSWER = "answer"
        const val PHASE_FAIL = "fail"
    }
}
