package app.eve.data.models

import kotlinx.serialization.Serializable

/**
 * GET /v1/today -> the persistent morning ritual, made re-readable + actionable.
 *
 * - [whys]        the owner's reasons-he-gets-up, recited verbatim (his anchor).
 * - [goals]       domain -> list of goal lines (faith / wealth / eating / …), order preserved.
 * - [strategy]    today's strategist narrative ("First… Second… Third…").
 * - [actionItems] the strategy parsed into discrete, checkable actions — the hero of the screen.
 *
 * Every field is optional/empty-tolerant: the backend degrades to empty lists when a source is
 * missing, so a sparse day still decodes cleanly. DEFAULT_JSON (ignoreUnknownKeys, isLenient)
 * carries the whole decode — no custom config here. `action_items` is wire snake_case; Kotlin
 * keeps the idiomatic [actionItems] via @SerialName at the property below.
 */
@Serializable
data class Today(
    val date: String = "",
    val user: String = "",
    val whys: List<String> = emptyList(),
    val goals: Map<String, List<String>> = emptyMap(),
    val strategy: String = "",
    @kotlinx.serialization.SerialName("action_items")
    val actionItems: List<String> = emptyList(),
) {
    /** True when the day has nothing to show (every section empty) — drives the empty state. */
    val isEmpty: Boolean
        get() = whys.isEmpty() && goals.isEmpty() && strategy.isBlank() && actionItems.isEmpty()
}
