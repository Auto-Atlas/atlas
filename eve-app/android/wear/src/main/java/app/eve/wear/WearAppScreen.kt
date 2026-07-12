package app.eve.wear

import app.eve.ASSISTANT_NAME
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * The single Wear screen for this increment: the Atlas wordmark over a phone-link status line.
 * Pure/stateless — it renders whatever [PhoneLinkState] it is handed, so every state is directly
 * unit-testable (no NodeClient, no coroutines). Dark, ambient-friendly Wear Material defaults
 * (black background, mono text); TimeText satisfies the Wear quality guideline for the top clock.
 */
@Composable
fun WearAppScreen(state: PhoneLinkState) {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$ASSISTANT_NAME",
                    style = MaterialTheme.typography.display1,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = phoneLinkLabel(state),
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The exact status line rendered for each link state. Kept separate from the composable so the unit
 * test can assert the copy for every state without a Compose harness. On failure the REAL exception
 * message is shown (project rule: fail loudly, never a fake "connected").
 */
fun phoneLinkLabel(state: PhoneLinkState): String = when (state) {
    is PhoneLinkState.Checking -> "Checking phone link…"
    is PhoneLinkState.Connected -> "Phone: connected"
    is PhoneLinkState.NotReachable -> "Phone: not reachable"
    is PhoneLinkState.Failed -> "Phone link failed: ${state.reason}"
}
