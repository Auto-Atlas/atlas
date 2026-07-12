package app.eve.wear.tile

import app.eve.ASSISTANT_NAME
import app.eve.data.models.SystemStatus
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.StatusSnapshot
import app.eve.wear.approvals.TestApprovals
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure JVM guard on the Tile/complication reducer — every [TileState] branch incl. the null-snapshot
 * cases, stale server-down, and the design contract that the pending COUNT comes from the approvals
 * snapshot (the list source), NOT SystemStatus.pendingApprovals.
 */
class WearStatusReaderTest {

    private val now = 1_000_000L

    private fun status(
        desktopOnline: Boolean = false,
        pendingApprovals: Int = 0,
        reachable: Boolean = true,
        atMs: Long = now,
        detail: String? = null,
    ) = StatusSnapshot(
        status = if (reachable || pendingApprovals > 0) {
            SystemStatus(desktopOnline = desktopOnline, pendingApprovals = pendingApprovals)
        } else {
            null
        },
        fetchedAtEpochMs = atMs,
        serverReachable = reachable,
        errorDetail = detail,
    )

    @Test
    fun both_null_is_never_synced() {
        assertEquals(TileState.NeverSynced, WearStatusReader.reduce(null, null, now))
    }

    @Test
    fun reachable_snapshot_is_live_with_count_and_desktop_and_age() {
        val approvals = ApprovalsSnapshot(
            approvals = listOf(TestApprovals.invoice("a1"), TestApprovals.channel("c1")),
            fetchedAtEpochMs = now - 30_000L,
            serverReachable = true,
        )
        val state = WearStatusReader.reduce(approvals, status(desktopOnline = true), now)
        assertEquals(TileState.Live(pendingCount = 2, desktopOnline = true, ageMs = 30_000L), state)
    }

    @Test
    fun live_count_comes_from_approvals_list_not_system_status_field() {
        // Approvals list has 2 rows; SystemStatus.pendingApprovals is a divergent 99 — the list wins.
        val approvals = ApprovalsSnapshot(
            approvals = listOf(TestApprovals.invoice("a1"), TestApprovals.invoice("a2")),
            fetchedAtEpochMs = now,
            serverReachable = true,
        )
        val state = WearStatusReader.reduce(approvals, status(pendingApprovals = 99, desktopOnline = true), now)
        assertEquals(2, (state as TileState.Live).pendingCount)
    }

    @Test
    fun reachable_empty_list_is_an_honest_live_zero() {
        val approvals = ApprovalsSnapshot(emptyList(), now, serverReachable = true)
        val state = WearStatusReader.reduce(approvals, status(desktopOnline = false), now)
        assertEquals(TileState.Live(pendingCount = 0, desktopOnline = false, ageMs = 0L), state)
    }

    @Test
    fun desktop_offline_flows_from_status_snapshot() {
        val approvals = ApprovalsSnapshot(listOf(TestApprovals.invoice("a1")), now, serverReachable = true)
        val state = WearStatusReader.reduce(approvals, status(desktopOnline = false), now)
        assertEquals(false, (state as TileState.Live).desktopOnline)
    }

    @Test
    fun approvals_present_status_missing_still_live_desktop_defaults_offline() {
        val approvals = ApprovalsSnapshot(listOf(TestApprovals.invoice("a1")), now, serverReachable = true)
        val state = WearStatusReader.reduce(approvals, null, now)
        assertEquals(TileState.Live(pendingCount = 1, desktopOnline = false, ageMs = 0L), state)
    }

    @Test
    fun approvals_missing_status_reachable_falls_back_to_system_status_count() {
        val state = WearStatusReader.reduce(null, status(desktopOnline = true, pendingApprovals = 4), now)
        assertEquals(TileState.Live(pendingCount = 4, desktopOnline = true, ageMs = 0L), state)
    }

    @Test
    fun server_down_from_approvals_carries_detail_and_no_fake_count() {
        // The phone writes an EMPTY list on an unreachable server — no fabricated "0 pending".
        val approvals = ApprovalsSnapshot(
            approvals = emptyList(),
            fetchedAtEpochMs = now - 300_000L,
            serverReachable = false,
            errorDetail = "cannot reach $ASSISTANT_NAME: timeout",
        )
        val state = WearStatusReader.reduce(approvals, status(reachable = false, atMs = now - 300_000L), now)
        assertEquals(
            TileState.ServerDown(detail = "cannot reach $ASSISTANT_NAME: timeout", pendingCountFromStale = null, ageMs = 300_000L),
            state,
        )
    }

    @Test
    fun server_down_surfaces_a_real_stale_count_when_the_snapshot_carries_one() {
        // Defensive: a down snapshot that still carries a non-empty list exposes it as the stale count.
        val approvals = ApprovalsSnapshot(
            approvals = listOf(TestApprovals.invoice("a1"), TestApprovals.channel("c1")),
            fetchedAtEpochMs = now,
            serverReachable = false,
            errorDetail = "unauthorized (401) — reconnect the phone",
        )
        val state = WearStatusReader.reduce(approvals, null, now) as TileState.ServerDown
        assertEquals(2, state.pendingCountFromStale)
        assertEquals("unauthorized (401) — reconnect the phone", state.detail)
    }

    @Test
    fun server_down_stale_count_falls_back_to_status_field_when_approvals_missing() {
        val status = status(reachable = false, pendingApprovals = 3, detail = "phone not connected to $ASSISTANT_NAME")
        val state = WearStatusReader.reduce(null, status, now) as TileState.ServerDown
        assertEquals(3, state.pendingCountFromStale)
        assertEquals("phone not connected to $ASSISTANT_NAME", state.detail)
    }

    @Test
    fun age_is_clamped_to_zero_when_snapshot_timestamp_is_in_the_future() {
        val approvals = ApprovalsSnapshot(emptyList(), now + 5_000L, serverReachable = true)
        val state = WearStatusReader.reduce(approvals, status(), now)
        assertEquals(0L, (state as TileState.Live).ageMs)
    }
}
