package app.eve.wear

/**
 * The four honest states of the watch<->phone link, driven by a REAL Data Layer node query
 * (see [PhoneNodeSource]). No optimistic/placeholder "connected" — the UI shows exactly what the
 * NodeClient reports, including the actual failure message when Play Services can't answer.
 */
sealed interface PhoneLinkState {
    /** The connected-nodes query is in flight (or not started yet). */
    data object Checking : PhoneLinkState

    /** At least one connected node — the phone is reachable over the Data Layer. */
    data class Connected(val nodeCount: Int) : PhoneLinkState

    /** The query succeeded but returned zero nodes — no phone paired/reachable right now. */
    data object NotReachable : PhoneLinkState

    /** The query threw (e.g. Play Services unavailable). [reason] is the real exception message. */
    data class Failed(val reason: String) : PhoneLinkState
}

/**
 * Pure reducer from a node-query outcome to a [PhoneLinkState]. Kept free of Android/Play-Services
 * types so it is trivially unit-testable across all four states:
 *  - `null`            -> [PhoneLinkState.Checking]   (query not yet complete)
 *  - failure           -> [PhoneLinkState.Failed]     (real exception message, never swallowed)
 *  - success, empty    -> [PhoneLinkState.NotReachable]
 *  - success, non-empty-> [PhoneLinkState.Connected]
 */
fun phoneLinkStateFrom(result: Result<List<String>>?): PhoneLinkState = when {
    result == null -> PhoneLinkState.Checking
    result.isFailure ->
        PhoneLinkState.Failed(result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "Play services unavailable")
    else -> {
        val nodes = result.getOrDefault(emptyList())
        if (nodes.isEmpty()) PhoneLinkState.NotReachable else PhoneLinkState.Connected(nodes.size)
    }
}

/**
 * Tiny seam over the Wear Data Layer NodeClient so the phone-link query can be faked in tests
 * (manual DI, no mocking library — matches the phone app's convention). Returns the connected
 * nodes' display names; MUST throw on a Play Services failure so the caller can surface it loudly.
 */
interface PhoneNodeSource {
    suspend fun connectedNodes(): List<String>
}
