package app.eve.wear.tile

import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.eve.wear.ui.WearEveColors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The EVE Status Tile. Renders the pending-approvals count + EVE desktop presence + freshness from a
 * [TileState] reduced ([WearStatusReader]) from the phone's retained snapshots. Tapping ANYWHERE on
 * the tile launches [app.eve.wear.MainActivity] (the approvals experience).
 *
 * Updates are EVENT-DRIVEN: EveDataListenerService calls TileService.getUpdater(...).requestUpdate
 * within seconds of the phone writing a snapshot. The 15-minute freshness interval is only a
 * belt-and-suspenders fallback (a stale "updated Nm ago" line self-refreshes) — NOT a polling loop.
 *
 * All copy comes from the pure, unit-tested [TileCopy]; this class is a thin ProtoLayout builder.
 * Honesty: [TileState.NeverSynced] renders "Waiting for phone", never a fabricated fresh 0.
 */
class EveTileService : TileService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = future {
        val state = TileStateReader.read(applicationContext)
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(rootLayout(state)))
            .setFreshnessIntervalMillis(FRESHNESS_FALLBACK_MS)
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = immediate(
        // Text-only tile — no image resources to map; just the version the timeline references.
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
    )

    // ---- Layout ---------------------------------------------------------------------------------

    /** Whole-tile tap -> MainActivity (the approvals experience), plus the dark EVE surface. */
    private fun rootLayout(state: TileState): LayoutElement {
        val clickable = Clickable.Builder()
            .setId("open_eve")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("app.eve.wear.MainActivity")
                            .build(),
                    )
                    .build(),
            )
            .build()

        val modifiers = Modifiers.Builder()
            .setClickable(clickable)
            .setBackground(Background.Builder().setColor(argb(WearEveColors.background.toArgb())).build())
            .setPadding(Padding.Builder().setAll(dp(12f)).build())
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(modifiers)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(content(state))
            .build()
    }

    private fun content(state: TileState): LayoutElement = when (state) {
        is TileState.Live -> liveColumn(state)
        is TileState.ServerDown -> serverDownColumn(state)
        TileState.NeverSynced -> neverSyncedColumn()
    }

    private fun liveColumn(s: TileState.Live): LayoutElement {
        val builder = centeredColumn()
            .addContent(
                text(
                    TileCopy.pendingCountText(s.pendingCount),
                    sizeSp = 40f,
                    colorArgb = WearEveColors.accent.toArgb(),
                    weight = LayoutElementBuilders.FONT_WEIGHT_BOLD,
                ),
            )
            .addContent(text(TileCopy.pendingLabel(s.pendingCount), sizeSp = 14f, colorArgb = WearEveColors.textSecondary.toArgb()))
            .addContent(spacer(6f))
            .addContent(
                text(
                    TileCopy.desktopLine(s.desktopOnline),
                    sizeSp = 12f,
                    colorArgb = (if (s.desktopOnline) WearEveColors.success else WearEveColors.textTertiary).toArgb(),
                ),
            )
            .addContent(text(TileCopy.freshness(s.ageMs), sizeSp = 11f, colorArgb = WearEveColors.textTertiary.toArgb()))
        return builder.build()
    }

    private fun serverDownColumn(s: TileState.ServerDown): LayoutElement {
        val builder = centeredColumn()
            .addContent(
                text(
                    TileCopy.serverDownHeadline(),
                    sizeSp = 15f,
                    colorArgb = WearEveColors.warning.toArgb(),
                    // FONT_WEIGHT_BOLD (not MEDIUM) — MEDIUM is @ProtoLayoutExperimental (opt-in error).
                    weight = LayoutElementBuilders.FONT_WEIGHT_BOLD,
                    maxLines = 2,
                ),
            )
        TileCopy.serverDownDetail(s.detail)?.let {
            builder.addContent(spacer(4f)).addContent(
                text(it, sizeSp = 12f, colorArgb = WearEveColors.textSecondary.toArgb(), maxLines = 3),
            )
        }
        TileCopy.staleLine(s.pendingCountFromStale)?.let {
            builder.addContent(spacer(2f)).addContent(
                text(it, sizeSp = 12f, colorArgb = WearEveColors.textTertiary.toArgb()),
            )
        }
        builder.addContent(spacer(4f)).addContent(
            text(TileCopy.serverDownAge(s.ageMs), sizeSp = 11f, colorArgb = WearEveColors.textTertiary.toArgb()),
        )
        return builder.build()
    }

    private fun neverSyncedColumn(): LayoutElement = centeredColumn()
        .addContent(text(TileCopy.neverSynced(), sizeSp = 16f, colorArgb = WearEveColors.textSecondary.toArgb(), maxLines = 2))
        .build()

    // ---- Small ProtoLayout helpers --------------------------------------------------------------

    private fun centeredColumn(): Column.Builder = Column.Builder()
        .setWidth(wrap())
        .setHeight(wrap())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    private fun text(
        value: String,
        sizeSp: Float,
        colorArgb: Int,
        weight: Int = LayoutElementBuilders.FONT_WEIGHT_NORMAL,
        maxLines: Int = 1,
    ): LayoutElement = Text.Builder()
        .setText(value)
        .setMaxLines(maxLines)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(sizeSp))
                .setColor(argb(colorArgb))
                .setWeight(weight)
                .build(),
        )
        .build()

    private fun spacer(heightDp: Float): LayoutElement =
        Spacer.Builder().setHeight(dp(heightDp)).build()

    // ---- ListenableFuture bridge ----------------------------------------------------------------

    /** Bridges a suspend build into the ListenableFuture the Tiles framework expects. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            val job = serviceScope.launch {
                try {
                    completer.set(block())
                } catch (t: Throwable) {
                    completer.setException(t)
                }
            }
            completer.addCancellationListener({ job.cancel() }, Runnable::run)
            "EveTileService#future"
        }

    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "EveTileService#immediate"
        }

    private companion object {
        const val RESOURCES_VERSION = "1"

        /** Fallback only — event-driven updates are primary. 15 min keeps the "updated Nm ago" honest. */
        const val FRESHNESS_FALLBACK_MS = 15L * 60L * 1000L
    }
}
