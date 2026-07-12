package app.eve.ui

import app.eve.ASSISTANT_NAME
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.eve.data.BaseUrl
import app.eve.data.EveConnection
import app.eve.data.Settings
import app.eve.ui.components.EveButton
import app.eve.ui.components.EveButtonStyle
import app.eve.ui.theme.EveTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

// No hardcoded credentials. Fresh installs start blank — the owner pairs by scanning the QR Atlas
// puts on screen ("Scan to connect"), or types the values once. Any saved value still wins.
private const val DEFAULT_BASE_URL = ""
private const val DEFAULT_TOKEN = ""

/**
 * The value a field should show given the saved value and the (now empty) default: a non-blank
 * saved value wins verbatim; a blank/null saved value (fresh install) falls back to [default].
 * Pure so it can be unit-tested without Compose / DataStore.
 */
internal fun initialFieldValue(saved: String?, default: String): String =
    saved?.takeIf { it.isNotBlank() } ?: default

/**
 * Parse a pairing payload (the QR Atlas shows): `eve://connect?base=<url>&token=<tok>` -> a
 * connection, or null if it isn't a valid Atlas pairing code. Pure (java.net.URI, not android.net)
 * so it is unit-testable on the JVM.
 */
internal fun parsePairingPayload(raw: String): EveConnection? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "eve") return null
    val params = (uri.rawQuery ?: "").split("&").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val k = java.net.URLDecoder.decode(part.substring(0, i), "UTF-8")
        val v = java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        k to v
    }.toMap()
    val base = params["base"]?.trim().orEmpty()
    val token = params["token"]?.trim().orEmpty()
    if (base.isBlank() || token.isBlank()) return null
    return EveConnection(base, token)
}

/**
 * First-run connection setup: scan Atlas's pairing QR, or enter the tailnet URL + app token.
 *
 * [onConfigSaved] fires after a successful manual "Save & connect" so the host can push the fresh
 * config to a paired watch (the live-voice door URL rides to the wrist over the Data Layer). Defaulted
 * to a no-op so previews/tests need no wiring.
 */
@Composable
fun ConnectScreen(settings: Settings, modifier: Modifier = Modifier, onConfigSaved: () -> Unit = {}) {
    val colors = EveTheme.colors
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(DEFAULT_BASE_URL) }
    var token by remember { mutableStateOf(DEFAULT_TOKEN) }
    // The PUBLIC live-voice door URL the WATCH dials (e.g. wss://eve-voice.<domain>/v1/watch/voice).
    // Optional: blank is fine (the watch shows "not configured"); it never affects phone connectivity.
    var watchVoiceDoorUrl by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Field state must not depend on the DataStore Flow's async timing: start blank, then once the
    // first persisted snapshot arrives, fill any saved non-blank value (a saved value wins).
    LaunchedEffect(Unit) {
        val persisted = settings.current()
        if (!seeded) {
            baseUrl = initialFieldValue(persisted.baseUrl, DEFAULT_BASE_URL)
            token = initialFieldValue(persisted.token, DEFAULT_TOKEN)
            watchVoiceDoorUrl = settings.watchVoiceDoorUrlNow()
            seeded = true
        }
    }

    // "Scan to connect": zxing returns the QR's raw string; a valid eve:// payload saves the
    // connection immediately (one-scan pairing). Anything else is reported, never silently saved.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw == null) return@rememberLauncherForActivityResult  // user cancelled
        val conn = parsePairingPayload(raw)
        if (conn == null || !conn.isConfigured) {
            error = "That QR isn't a valid $ASSISTANT_NAME pairing code."
        } else {
            baseUrl = conn.baseUrl
            token = conn.token
            error = null
            scope.launch { settings.set(conn.baseUrl, conn.token) }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        cursorColor = colors.accent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderDefault,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textTertiary,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .padding(EveTheme.spacing.gutterScreen),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        Text("Connect to $ASSISTANT_NAME", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
        Text(
            "Ask $ASSISTANT_NAME to \"pair my phone\", then scan the QR she puts on screen.",
            style = EveTheme.type.body.copy(color = colors.textSecondary),
        )
        EveButton(
            text = "Scan to connect",
            onClick = {
                error = null
                scanLauncher.launch(
                    ScanOptions().apply {
                        setPrompt("Point at $ASSISTANT_NAME's pairing QR")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "or enter it manually",
            style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; error = null },
            label = { Text("Base URL (e.g. https://host.ts.net:8443)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text("App token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        // Optional: the PUBLIC live-voice door your WATCH dials for the real-call feature. Leave blank
        // if you don't use the watch live voice — the watch will simply show "not configured".
        OutlinedTextField(
            value = watchVoiceDoorUrl,
            onValueChange = { watchVoiceDoorUrl = it; error = null },
            label = { Text("Watch voice door URL (e.g. wss://eve-voice.example.com/v1/watch/voice)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, style = EveTheme.type.bodySm.copy(color = colors.danger)) }
        EveButton(
            text = "Save & connect",
            style = EveButtonStyle.Subtle,
            onClick = onClick@{
                val normalized = BaseUrl.normalize(baseUrl)
                val tok = token.trim()
                if (normalized == null) {
                    error = "Enter a valid URL like https://host.ts.net:8443 (http/https + host)."
                    return@onClick
                }
                if (tok.isBlank()) {
                    error = "App token is required."
                    return@onClick
                }
                scope.launch {
                    settings.set(normalized, tok)
                    // Persist the watch voice-door URL (trimmed; blank clears it) alongside the
                    // connection, then let the host push the fresh config to the wrist.
                    settings.setWatchVoiceDoorUrl(watchVoiceDoorUrl)
                    onConfigSaved()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
