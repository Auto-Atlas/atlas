package app.eve.ui.talk

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.eve.ui.theme.EveTheme
import app.eve.ui.theme.JetBrainsMono
import app.eve.visual.ImageLoad
import app.eve.visual.SurfaceVisual
import app.eve.visual.VisualCard

/**
 * The surface_visual card for the Talk screen — a sibling of the tool-call / delegation surfaces
 * (same EveTheme tokens, raised dark card, subtle border). EVE SHOWS something here instead of only
 * saying it: a live desktop screenshot, an image, or a text/log note. Images load async with a
 * shimmer placeholder and degrade honestly (expired / failed); tapping an image opens a pinch-zoom
 * full-screen viewer. The card is dismissible and never blocks the voice UI.
 */
@Composable
fun VisualCardView(card: VisualCard, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EveTheme.colors
    // Reset the full-screen flag whenever the card instance changes (a new visual arrived).
    var fullScreen by remember(card) { mutableStateOf(false) }
    val loaded = card.image as? ImageLoad.Loaded

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.borderSubtle, EveTheme.shape.lg)
            .padding(EveTheme.spacing.padCard),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = kindIcon(card.visual.kind),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(EveTheme.spacing.s2))
            Text(
                text = "EVE is showing you",
                style = EveTheme.type.micro.copy(color = colors.textTertiary),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Dismiss this visual",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Dismiss this visual") { onDismiss() },
            )
        }

        Spacer(Modifier.height(EveTheme.spacing.s1))
        Text(
            text = card.visual.title,
            style = EveTheme.type.headline.copy(color = colors.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(EveTheme.spacing.s3))

        when (val img = card.image) {
            is ImageLoad.NoImage -> NoteBody(card.visual.text)
            is ImageLoad.Loading -> ShimmerBox()
            is ImageLoad.Loaded -> Box {
                ComposeImage(
                    bitmap = img.bitmap,
                    contentDescription = card.visual.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(EveTheme.shape.md)
                        .clickable(onClickLabel = "Open full screen") { fullScreen = true }
                        .semantics { contentDescription = "${card.visual.title}. Tap to open full screen." },
                )
                // Affordance that the image zooms.
                Icon(
                    imageVector = Icons.Outlined.ZoomIn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(5.dp),
                )
            }
            is ImageLoad.Expired -> StateMessage(
                icon = Icons.Outlined.BrokenImage,
                text = "This image expired — ask EVE to show it again.",
                tint = colors.warning,
            )
            is ImageLoad.Failed -> StateMessage(
                icon = Icons.Outlined.BrokenImage,
                text = img.message,
                tint = colors.danger,
            )
        }
    }

    if (fullScreen && loaded != null) {
        FullScreenImage(image = loaded, title = card.visual.title, onClose = { fullScreen = false })
    }
}

/** Monospace-ish, scrollable block for note/log content (errors, lists, a delegation's log). */
@Composable
private fun NoteBody(text: String) {
    val colors = EveTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.md)
            .background(colors.surfaceCanvas)
            .border(1.dp, colors.borderSubtle, EveTheme.shape.md)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(EveTheme.spacing.s3),
    ) {
        Text(
            text = text,
            style = EveTheme.type.bodySm.copy(fontFamily = JetBrainsMono, color = colors.textSecondary),
        )
    }
}

/** A pulsing placeholder box while the image is being fetched + decoded. */
@Composable
private fun ShimmerBox() {
    val colors = EveTheme.colors
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(EveTheme.shape.md)
            .background(colors.surfaceRaised2.copy(alpha = a)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Loading…",
            style = EveTheme.type.caption.copy(color = colors.textTertiary),
        )
    }
}

@Composable
private fun StateMessage(icon: ImageVector, text: String, tint: Color) {
    val colors = EveTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.md)
            .background(colors.surfaceCanvas)
            .padding(EveTheme.spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(EveTheme.spacing.s2))
        Text(text = text, style = EveTheme.type.bodySm.copy(color = colors.textSecondary))
    }
}

/**
 * Full-bleed image viewer with pinch-to-zoom + pan (transformable), tap-anywhere and a Close button
 * to dismiss (Dialog also handles the system Back). Scale is clamped to [1x, 5x] and pan resets when
 * zoomed back out so the image can't get lost off-screen.
 */
@Composable
private fun FullScreenImage(image: ImageLoad.Loaded, title: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale > 1f) {
                offsetX += panChange.x
                offsetY += panChange.y
            } else {
                offsetX = 0f
                offsetY = 0f
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClickLabel = "Close") { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            ComposeImage(
                bitmap = image.bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(state = transformState),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close full screen",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClickLabel = "Close full screen") { onClose() }
                    .padding(8.dp),
            )
        }
    }
}

private fun kindIcon(kind: SurfaceVisual.Kind): ImageVector = when (kind) {
    SurfaceVisual.Kind.DESKTOP_SCREEN -> Icons.Outlined.Computer
    SurfaceVisual.Kind.IMAGE -> Icons.Outlined.Image
    SurfaceVisual.Kind.NOTE -> Icons.Outlined.Notes
}
