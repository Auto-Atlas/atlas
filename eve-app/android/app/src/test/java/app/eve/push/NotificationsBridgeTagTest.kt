package app.eve.push

import android.app.Application
import androidx.core.app.NotificationCompat
import app.eve.R
import app.eve.data.wear.WearLink
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the tag-scoped bridging contract on the PHONE side: the approval notification carries the
 * [WearLink.BRIDGE_TAG_APPROVAL] wear bridge tag (so the watch, which excludes that tag, owns the
 * wrist approval and the owner never gets a double notification), while a non-approval notification
 * (e.g. the foreground stream notification) does NOT — it keeps default auto-bridging to the wrist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsBridgeTagTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun bridgeTagOf(n: android.app.Notification): String? =
        NotificationCompat.WearableExtender(n).bridgeTag

    @Test
    fun approval_notification_carries_the_approval_bridge_tag() {
        val n = Notifications.buildApprovalNotification(
            context = app,
            approvalId = "a1",
            title = "$1,200 invoice",
            body = "Requested by Jamie",
        )
        assertEquals(WearLink.BRIDGE_TAG_APPROVAL, bridgeTagOf(n))
    }

    @Test
    fun a_non_approval_stream_notification_keeps_default_bridging() {
        // Built exactly as StreamService builds its foreground notification — no WearableExtender tag,
        // so it auto-bridges to the wrist like every other non-approval notification.
        Notifications.ensureChannels(app)
        val stream = NotificationCompat.Builder(app, Notifications.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("EVE is listening")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        assertNull(bridgeTagOf(stream), "non-approval notifications must NOT carry the approval bridge tag")
    }
}
