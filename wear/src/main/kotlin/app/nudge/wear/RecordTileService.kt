package app.nudge.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.nudge.core.Status
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val RES_VERSION = "1"
private const val MIC = "mic"

/** Swipe to this tile from the watch face; one tap on the big button starts recording. */
class RecordTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val device = requestParams.deviceConfiguration
        val state = WatchData.load(this)
        val record = ModifiersBuilders.Clickable.Builder()
            .setId("record")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WatchCaptureActivity::class.java.name)
                            .build(),
                    ).build(),
            ).build()

        val button = Button.Builder(this, record)
            .setIconContent(MIC)
            .setSize(ButtonDefaults.EXTRA_LARGE_SIZE)
            .setButtonColors(ButtonColors(argb(0xFFE5484D.toInt()), argb(0xFFFFFFFF.toInt())))
            .setContentDescription("Record a reminder")
            .build()

        val firing = state.items.count { it.status == Status.FIRING }
        val next = state.items.firstOrNull { it.status != Status.FIRING }
        val line = when {
            firing > 0 -> "$firing need${if (firing == 1) "s" else ""} you"
            next != null -> next.text
            else -> "Tap to remember"
        }
        val sub = if (firing == 0 && next != null) next.description else ""

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(button)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                Text.Builder(this, line)
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .setMaxLines(1)
                    .build(),
            )
        if (sub.isNotEmpty()) column.addContent(
            Text.Builder(this, sub)
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .setColor(argb(0xFFFFB86B.toInt()))
                .setMaxLines(1)
                .build(),
        )

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RES_VERSION)
                .addIdToImageMapping(
                    MIC,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build(),
                        ).build(),
                ).build(),
        )
}

/** A watch-face complication: one tap from the watch face starts recording. */
class RecordComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = data(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type)

    private fun data(type: ComplicationType): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, WatchCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val desc = PlainComplicationText.Builder("Record a reminder").build()
        val mono = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build()
        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic_round), SmallImageType.ICON).build(), desc,
            ).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mono, desc)
                .setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder("Nudge").build(), desc)
                .setMonochromaticImage(mono).setTapAction(tap).build()
            else -> null
        }
    }
}
