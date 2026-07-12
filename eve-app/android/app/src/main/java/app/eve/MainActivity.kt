package app.eve

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.eve.push.Notifications
import app.eve.push.StreamService
import app.eve.ritual.RitualNotification
import app.eve.ui.ConnectScreen
import app.eve.ui.EveApp
import app.eve.ui.EveViewModelFactory
import app.eve.ui.onboarding.OnboardingScreen
import app.eve.ui.onboarding.OnboardingViewModel
import app.eve.ui.theme.EveTheme
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Single-activity host. Starts the foreground StreamService while visible (onStart) and stops it
 * when backgrounded (onStop) — the live connection is held ONLY while the app is open. First run
 * shows the ConnectScreen until a base URL + token are stored.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as EveApplication).container }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way, proceed */ }

    // Reactive so a notification Review tap that arrives via onNewIntent (app already running)
    // updates the UI, not just a cold start.
    private val pendingOpenCard = mutableStateOf<String?>(null)

    // First-run gate, seeded from the persisted SharedPreferences flag. Mutable so finishing the
    // wizard (or relaunching it from Settings) flips the UI without an Activity restart.
    private val onboardingComplete = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()

        val launchedForRitual = applyRitualLaunch(intent)
        pendingOpenCard.value = openCardFromIntent(intent)
        // Seed the first-run gate from the persisted flag (local + simple — never server state).
        onboardingComplete.value = container.onboardingState.isComplete

        setContent {
            EveTheme(dark = true) {
                val connection by container.settings.connection.collectAsState(
                    initial = app.eve.data.EveConnection("", ""),
                )
                when {
                    // Pairing comes first: onboarding needs the configured connection to reach the
                    // server (POST /v1/identity, /v1/enroll).
                    !connection.isConfigured -> ConnectScreen(
                        settings = container.settings,
                        // Push the freshly-saved config (incl. the watch voice-door URL + token) to a
                        // paired watch over the Data Layer. Fire-and-forget; a Data-Layer write failure
                        // (e.g. no watch paired) is caught + logged inside the bridge/gateway.
                        onConfigSaved = {
                            lifecycleScope.launch {
                                runCatching { container.wearBridge.refreshSnapshots() }
                            }
                        },
                    )

                    // First run (or a re-run requested from Settings): the owner isn't set up yet.
                    !onboardingComplete.value -> {
                        val vm: OnboardingViewModel = viewModel(factory = EveViewModelFactory(container))
                        OnboardingScreen(
                            viewModel = vm,
                            // The VM already flipped the persisted flag in complete(); mirror it here
                            // so the UI swaps to the app immediately.
                            onFinished = { onboardingComplete.value = true },
                        )
                    }

                    else -> EveApp(
                        container = container,
                        openCardId = pendingOpenCard.value,
                        // Reset once handled so the same card can't re-fire on a recomposition or a
                        // config-change recreate (the launch Intent persists via setIntent()).
                        onOpenCardConsumed = { pendingOpenCard.value = null },
                        autoStartRitual = launchedForRitual,
                        // Re-run setup / re-enroll voice from Settings: clear the gate and swap back
                        // to the wizard in place (no Activity restart needed).
                        onReRunSetup = {
                            container.onboardingState.reset()
                            onboardingComplete.value = false
                        },
                    )
                }
            }
        }
    }

    /**
     * The approval id to open, from either the notification Review extra (in-app push) OR the
     * `eve://approvals/{id}` deep-link the ntfy "Review" action fires (approval_push.py). The deep
     * link puts the id in the path; the extra is a plain string.
     */
    private fun openCardFromIntent(launchIntent: Intent?): String? {
        launchIntent?.getStringExtra(Notifications.EXTRA_OPEN_CARD)?.let { return it }
        val data = launchIntent?.data ?: return null
        if (data.scheme != "eve" || data.host != "approvals") return null
        return data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The morning-ritual alarm launches this activity (via the full-screen intent) while the phone
     * may be locked and asleep. Show over the keyguard and turn the screen on so Atlas's wake-up
     * actually reaches the user, and clear the alarm notification. Returns true when this launch is
     * a ritual, so the UI auto-connects to the Talk screen.
     */
    private fun applyRitualLaunch(launchIntent: Intent?): Boolean {
        val isRitual = launchIntent?.getStringExtra(RitualNotification.EXTRA_RITUAL) ==
            RitualNotification.RITUAL_MORNING
        if (!isRitual) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        RitualNotification.cancel(this)
        return true
    }

    /**
     * singleTask: a ritual alarm that fires while the app is already running arrives here. Re-apply
     * the lock-screen flags + re-run setContent with the new intent so it auto-connects.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(RitualNotification.EXTRA_RITUAL) == RitualNotification.RITUAL_MORNING) {
            recreate()
            return
        }
        // A notification Review tap OR an eve://approvals/{id} deep-link while the app is already
        // running: route to the primed card. Updating this Compose state navigates to Approvals and
        // primes the card open (EveApp).
        openCardFromIntent(intent)?.let { cardId ->
            pendingOpenCard.value = cardId
        }
    }

    override fun onStart() {
        super.onStart()
        StreamService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Connection is held only while the app is open.
        StreamService.stop(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
