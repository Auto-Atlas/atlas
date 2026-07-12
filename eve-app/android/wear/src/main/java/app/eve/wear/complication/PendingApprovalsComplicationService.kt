package app.eve.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.eve.wear.MainActivity
import app.eve.wear.R
import app.eve.wear.tile.TileState
import app.eve.wear.tile.TileStateReader

/**
 * A watch-face complication for the Atlas pending-approvals count. Supports SHORT_TEXT (the number,
 * titled "Atlas") and RANGED_VALUE (the number as an arc). Reads the SAME phone snapshots the Tile
 * does via [TileStateReader]; all rendering strings/values come from the pure, unit-tested
 * [ComplicationCopy]. Tapping opens [MainActivity].
 *
 * Honesty: only [TileState.Live] shows a number. [TileState.NeverSynced] / [TileState.ServerDown]
 * show "—" with a content description that says why ("waiting for phone" / "server unreachable") —
 * a screen reader never announces a fabricated fresh 0.
 *
 * Updates are push-driven (UPDATE_PERIOD_SECONDS=0): EveDataListenerService fires
 * ComplicationDataSourceUpdateRequester.requestUpdateAll when the phone writes a snapshot.
 */
class PendingApprovalsComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val state = TileStateReader.read(applicationContext)
        return build(request.complicationType, state) ?: NoDataComplicationData()
    }

    /**
     * Preview shown in the watch-face complication PICKER. This is EXPLICITLY allowed to be synthetic
     * — its whole purpose is to show the user what the complication looks like when populated, not to
     * report a runtime reading. A representative count of 3 pending.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, PREVIEW_STATE)

    private fun build(type: ComplicationType, state: TileState): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = plain(ComplicationCopy.shortText(state)),
            contentDescription = plain(ComplicationCopy.contentDescription(state)),
        )
            .setTitle(plain(ComplicationCopy.TITLE))
            .setMonochromaticImage(eveMark())
            .setTapAction(tapIntent())
            .build()

        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
            value = ComplicationCopy.rangedValue(state),
            min = ComplicationCopy.rangedMin(),
            max = ComplicationCopy.rangedMax(state),
            contentDescription = plain(ComplicationCopy.contentDescription(state)),
        )
            .setText(plain(ComplicationCopy.shortText(state)))
            .setTitle(plain(ComplicationCopy.TITLE))
            .setMonochromaticImage(eveMark())
            .setTapAction(tapIntent())
            .build()

        else -> null // Only the two types we declare in SUPPORTED_TYPES are ever requested.
    }

    private fun plain(text: String): ComplicationText =
        PlainComplicationText.Builder(text).build()

    private fun eveMark(): MonochromaticImage =
        MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_eve_mark)).build()

    /**
     * Tapping the complication opens the approvals experience. No launch flags (the official
     * complication sample uses a plain intent): PendingIntent.getActivity supplies the task context,
     * and omitting FLAG_ACTIVITY_NEW_TASK/CLEAR_TOP keeps MainActivity correct in Wear recents.
     */
    private fun tapIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        val PREVIEW_STATE = TileState.Live(pendingCount = 3, desktopOnline = true, ageMs = 0L)
    }
}
