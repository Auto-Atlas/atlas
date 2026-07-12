package app.eve.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eve.data.EveWireJson
import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.SystemStatus
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.StatusSnapshot
import app.eve.data.wear.WearLink
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ON-DEVICE integration test for the watch snapshot pipeline. Writes the canonical approvals
 * fixture (the same committed approvals_sample.json the phone/shared suites pin) through the REAL
 * Play-Services DataClient on the local node, then reads it back through the app's own decode path.
 *
 * Because the DataItems are RETAINED, running this on an emulator also stages the full pipeline for
 * manual/visual verification without a paired phone: launching the app afterwards exercises
 * DataClient -> listener -> decode -> ViewModel -> list/detail UI, EveDataListenerService fires the
 * tile/complication refresh, and ApprovalNotifier posts the wrist notification — all production
 * code. The phone<->watch RADIO hop is the only leg this cannot cover (hardware-gated).
 *
 * Times in the fixture are re-based to "now" so the staged approvals are genuinely pending (the
 * committed fixture's absolute timestamps are long expired).
 */
@RunWith(AndroidJUnit4::class)
class SnapshotPipelineTest {

    @Test
    fun retainedSnapshotWrite_roundTripsThroughTheRealDataLayer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataClient = Wearable.getDataClient(context)

        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json")) {
            "missing approvals_sample.json in androidTest resources"
        }.bufferedReader().use { it.readText() }

        val now = System.currentTimeMillis()
        val nowS = now / 1000.0
        val approvals = EveWireJson.decodeFromString(ApprovalsResponse.serializer(), fixture)
            .approvals
            .map { it.copy(createdAt = nowS, expiresAt = nowS + it.ttlSeconds, secondsLeft = it.ttlSeconds.toDouble()) }
        assertTrue("fixture must stage at least one approval", approvals.isNotEmpty())

        val approvalsSnapshot = ApprovalsSnapshot(approvals, now, serverReachable = true)
        val statusSnapshot = StatusSnapshot(
            status = SystemStatus(desktopOnline = true, pendingApprovals = approvals.size),
            fetchedAtEpochMs = now,
            serverReachable = true,
        )

        awaitDataClientTask {
            dataClient.putDataItem(
                PutDataRequest.create(WearLink.PATH_APPROVALS_SNAPSHOT)
                    .setData(approvalsSnapshot.toBytes()).setUrgent(),
            )
        }
        awaitDataClientTask {
            dataClient.putDataItem(
                PutDataRequest.create(WearLink.PATH_STATUS_SNAPSHOT)
                    .setData(statusSnapshot.toBytes()).setUrgent(),
            )
        }

        // Read back through the app's OWN shared read/decode path — the exact code the app,
        // tile, and complication use.
        val latest = dataClient.latestSnapshots()
        val readBack = requireNotNull(latest.approvals) { "approvals snapshot missing after write" }
        assertTrue(readBack.serverReachable)
        assertEquals(approvals.map { it.id }, readBack.approvals.map { it.id })
        assertEquals(approvals.size, requireNotNull(latest.status).status?.pendingApprovals)
    }
}
