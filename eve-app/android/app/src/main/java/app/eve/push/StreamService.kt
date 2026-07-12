package app.eve.push

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.eve.EveApplication
import app.eve.R
import app.eve.data.models.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that holds the /v1/stream WebSocket ONLY while the app is open (started in
 * MainActivity.onStart, stopped in onStop). It raises approval notifications for
 * approval_pending events and cancels them on approval_resolved/expired (no ghost buttons).
 *
 * It deliberately does not poll in the background or run when the app is closed — push for the
 * closed-app case is delivered by ntfy (the self-hosted server side), and Review/Deny actions
 * route through Notifications/ApprovalActionReceiver.
 */
class StreamService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var streamJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On Android 14+ startForeground() can throw (ForegroundServiceStartNotAllowedException
        // when started from the background, SecurityException on type/permission mismatch, or a
        // plain RuntimeException if the FGS notification can't post). Any throw escaping
        // onStartCommand crashes the WHOLE app — and the live stream is only an enhancement, so it
        // must NEVER take the app down. If we can't promote to foreground, degrade gracefully:
        // run the WebSocket anyway while we're alive (best-effort) and let the OS reap us normally.
        val promoted = startInForeground()
        startStreaming()
        return restartMode(promoted)
    }

    /** Returns true if the service was successfully promoted to the foreground. Never throws. */
    private fun startInForeground(): Boolean {
        return try {
            // Channel MUST exist before startForeground or posting the FGS notification fails.
            Notifications.ensureChannels(this)
            val notification: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_STREAM)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.stream_channel_name))
                .setContentText(getString(R.string.stream_channel_desc))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException (API 34+) is a subclass of
            // IllegalStateException, so a broad Throwable catch covers every documented and
            // undocumented failure mode. Degrade instead of crash.
            Log.w(TAG, "Could not start foreground stream service; running degraded (no FGS).", t)
            false
        }
    }

    private fun startStreaming() {
        if (streamJob?.isActive == true) return
        val app = applicationContext as EveApplication
        val streamClient = app.container.streamClient
        streamJob = scope.launch {
            // Reconnect loop with backoff while the service is alive.
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    streamClient.events()
                        .catch { /* surface as completion; loop reconnects */ }
                        .collect { event ->
                            handleEvent(this@StreamService, event)
                            backoffMs = 1_000L
                        }
                } catch (_: Throwable) {
                    // fall through to backoff
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun handleEvent(context: Context, event: StreamEvent) {
        when {
            event.isCaptureFrame -> {
                // look_via_phone: EVE wants a camera frame. The controller is foreground-only and
                // single-in-flight; it no-ops (logs) when the app isn't visible or permission is off.
                (context.applicationContext as EveApplication).container.frameCaptureController.onEvent(event)
            }
            event.isSurfaceVisual -> {
                // surface_visual: EVE is SHOWING something. The hub validates + fetches/decodes the
                // image and publishes the latest card as state the Talk screen renders.
                (context.applicationContext as EveApplication).container.visualHub.onEvent(event)
            }
            event.isResolved || event.isExpired -> {
                event.id?.let { Notifications.cancel(context, it) }
            }
            event.isPending -> {
                event.id?.let { id ->
                    val n = Notifications.buildApprovalNotification(
                        context = context,
                        approvalId = id,
                        title = "Approval waiting",
                        body = "A known family member has a high-risk request. Tap Review.",
                    )
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .let { mgr ->
                            if (mgr.areNotificationsEnabled()) {
                                mgr.notify(Notifications.notificationId(id), n)
                            }
                        }
                }
            }
        }

        // An approval appearing/resolving/expiring shifts what the watch shows → push a fresh
        // snapshot over the Data Layer. Fire-and-forget in the service scope; a Data-Layer write
        // failure (e.g. no watch paired) is caught + logged inside the bridge/gateway, never a crash.
        // This is EVENT-DRIVEN only — there is no polling.
        if (shouldRefreshWear(event)) {
            val bridge = (context.applicationContext as EveApplication).container.wearBridge
            scope.launch {
                try {
                    bridge.refreshSnapshots()
                } catch (t: Throwable) {
                    Log.e(TAG, "wear snapshot refresh on '${event.type}' failed", t)
                }
            }
        }
    }

    override fun onDestroy() {
        streamJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 4201
        private const val TAG = "StreamService"

        /**
         * Pure restart-policy decision (unit-tested). If we were promoted to the foreground we want
         * START_STICKY so the OS keeps the live connection alive while the app is open. If promotion
         * FAILED we must return START_NOT_STICKY: a sticky restart would happen from the background
         * and re-throw on the next startForeground attempt — the exact crash we are guarding against.
         */
        fun restartMode(promoted: Boolean): Int = if (promoted) START_STICKY else START_NOT_STICKY

        /**
         * Pure decision (unit-tested): does this stream event change the approval set the watch
         * mirrors? approval_pending / approval_resolved / approval_expired all do — each shifts the
         * watch's next snapshot. Tool-call / delegation / vision / surface-visual events do NOT touch
         * approvals, so they must NOT trigger a Data-Layer write (battery honesty — no needless sync).
         */
        fun shouldRefreshWear(event: StreamEvent): Boolean =
            event.isPending || event.isResolved || event.isExpired

        /**
         * Best-effort start. startForegroundService() itself throws
         * ForegroundServiceStartNotAllowedException if called while the app is in the background on
         * Android 12+, so this is wrapped too. Failing to start the live stream must never crash
         * the app — the screens poll independently.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StreamService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed; skipping live stream.", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StreamService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopService failed.", t)
            }
        }
    }
}
