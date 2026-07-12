package app.eve.ui.talk

import app.eve.data.models.StreamEvent

/**
 * The live "what is EVE doing right now" state for the Talk screen — the Android analogue of the
 * desktop `useJarvisBridge` reducer (app/frontend/src/hooks/useJarvisBridge.ts). It folds the
 * republished `tool_call` / `tool_result` / `delegation_*` / `thinking` [StreamEvent]s into the
 * shape the transformative UI renders: the per-brain delegation waterfall, the current tool, and
 * whether EVE is thinking.
 *
 * Deliberately PURE (no Compose, no Android, no clock) so it is fully JVM-unit-testable: the
 * caller passes `nowMs` so tool latency is deterministic in tests. Mirrors the desktop's rules
 * exactly — most importantly the `working` heartbeat only updates the live "16s" label and never
 * appends a step (useJarvisBridge.ts:419-427).
 */

enum class ToolStatus { RUNNING, OK, ERROR }

/** One invocation of a tool, with its running→done lifecycle and measured latency. */
data class ToolActivity(
    val tool: String,
    val status: ToolStatus,
    val detail: String? = null,
    val latencyMs: Long? = null,
    val startedAtMs: Long,
)

/** One brain's latest reported step in a delegation. */
data class DelegationStep(
    val brain: String,
    val phase: String, // try | working | answer | fail
    val detail: String? = null,
    val ok: Boolean? = null,
    val latencyMs: Long? = null,
)

/** A delegation (a jarvis_agent hand-off) and its evolving per-brain waterfall. */
data class DelegationState(
    val delegId: String,
    val task: String? = null,
    val brains: List<String> = emptyList(),
    val steps: List<DelegationStep> = emptyList(),
    /** The brain currently being tried (drives the live "working Ns" row); null when none. */
    val activeBrain: String? = null,
    /** The live detail on the active brain (e.g. "16s"); null between/after steps. */
    val activeDetail: String? = null,
    val done: Boolean = false,
    val ok: Boolean? = null,
    val winner: String? = null,
    val totalLatencyMs: Long? = null,
) {
    /**
     * Latest step per brain, in [brains] order with any extra brains appended — the row model the
     * ticker renders (mirrors useJarvisBridge's `byBrain` map + brains[] ordering).
     */
    fun rows(): List<DelegationStep> {
        val latest = LinkedHashMap<String, DelegationStep>()
        for (s in steps) latest[s.brain] = s
        val ordered = ArrayList<DelegationStep>()
        for (b in brains) {
            latest[b]?.let { ordered.add(it) }
                ?: ordered.add(DelegationStep(brain = b, phase = "queued"))
        }
        // Any brain that produced a step but wasn't in the announced chain.
        for ((b, s) in latest) if (brains.none { it == b }) ordered.add(s)
        return ordered
    }
}

/** The combined live activity the Talk UI observes. All-null/false = calm (idle). */
data class LiveActivity(
    val delegation: DelegationState? = null,
    val tool: ToolActivity? = null,
    val thinking: Boolean = false,
) {
    /** True while a tool call or delegation is in flight — the "working" condition that the orb and
     *  status surfaces react to (desktop: `delegation && !done` or `toolActivity.running`). */
    val isWorking: Boolean
        get() = (delegation != null && !delegation.done) ||
            (tool != null && tool.status == ToolStatus.RUNNING)

    companion object {
        val IDLE = LiveActivity()
    }
}

/**
 * The pure transition. Total over (state, event): an event we don't model holds the state. Pass a
 * monotonic-ish [nowMs] (e.g. System.currentTimeMillis) so tool latency is computed deterministically.
 */
fun reduceActivity(state: LiveActivity, e: StreamEvent, nowMs: Long): LiveActivity = when {
    e.isToolCall -> state.copy(
        tool = ToolActivity(
            tool = e.tool ?: "?",
            status = ToolStatus.RUNNING,
            detail = e.args ?: e.detail,
            startedAtMs = nowMs,
        ),
    )

    e.isToolResult -> {
        val tool = e.tool ?: "?"
        val prev = state.tool
        val status = if (e.ok == true) ToolStatus.OK else ToolStatus.ERROR
        val updated = if (prev != null && prev.tool == tool && prev.status == ToolStatus.RUNNING) {
            prev.copy(status = status, detail = e.detail ?: prev.detail, latencyMs = nowMs - prev.startedAtMs)
        } else {
            // Result with no matching running call (missed the call frame) — show it complete.
            ToolActivity(tool = tool, status = status, detail = e.detail, latencyMs = null, startedAtMs = nowMs)
        }
        state.copy(tool = updated)
    }

    e.isDelegationStart -> state.copy(
        delegation = DelegationState(
            delegId = e.delegId ?: "",
            task = e.task,
            brains = e.brains ?: emptyList(),
        ),
    )

    e.isDelegationStep -> {
        val d = state.delegation
        if (d == null || (e.delegId != null && e.delegId != d.delegId)) {
            state // a step with no/over-stale delegation: ignore (never corrupt the waterfall)
        } else if (e.phase == StreamEvent.PHASE_WORKING) {
            // Heartbeat: only refresh the live label, never append a step.
            state.copy(delegation = d.copy(activeBrain = e.brain ?: d.activeBrain, activeDetail = e.detail))
        } else {
            val step = DelegationStep(
                brain = e.brain ?: "?",
                phase = e.phase ?: "?",
                detail = e.detail,
                ok = e.ok,
                latencyMs = e.latencyMs,
            )
            val active = e.phase == StreamEvent.PHASE_TRY
            state.copy(
                delegation = d.copy(
                    steps = d.steps + step,
                    activeBrain = if (active) e.brain else null,
                    activeDetail = null,
                ),
            )
        }
    }

    e.isDelegationEnd -> {
        val d = state.delegation
        if (d == null || (e.delegId != null && e.delegId != d.delegId)) {
            state
        } else {
            state.copy(
                delegation = d.copy(
                    done = true,
                    ok = e.ok,
                    winner = e.brain,
                    totalLatencyMs = e.totalLatencyMs,
                    activeBrain = null,
                    activeDetail = null,
                ),
            )
        }
    }

    e.isThinking -> state.copy(thinking = e.active ?: state.thinking)

    else -> state
}
