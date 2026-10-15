package app.nudge.core

import kotlinx.serialization.Serializable

/** Paths and payloads shared by the phone and the watch over the Wear OS Data Layer. */
object Paths {
    /** Phone -> watch: everything the watch needs to parse and list (DataItem). */
    const val STATE = "/nudge/state"

    /** Watch -> phone: a captured reminder, one DataItem per capture so offline captures queue up. */
    const val CAPTURE_PREFIX = "/nudge/capture/"

    /** Watch -> phone message: done / sleep on a reminder. */
    const val ACTION = "/nudge/action"

    /** Phone -> watch message: a reminder just fired, buzz hard. */
    const val BUZZ = "/nudge/buzz"

    /** Phone -> watch message: notification was handled, stop buzzing. */
    const val QUIET = "/nudge/quiet"

    /** Watch -> phone channel: live 16 kHz mono PCM while recording. Path is followed by "/<capture id>". */
    const val AUDIO_STREAM = "/nudge/audio-stream"

    /** Phone -> watch message: the finished [WatchDraft] as JSON. */
    const val RESULT = "/nudge/result"

    /** Watch -> phone DataItem with the recording, when the phone couldn't be reached live. */
    const val AUDIO_PREFIX = "/nudge/audio/"

    const val CAPABILITY_WATCH = "nudge_watch"
    const val CAPABILITY_PHONE = "nudge_phone"
}

@Serializable
data class WatchItem(
    val id: String,
    val text: String,
    val description: String,
    val status: Status,
    val sortKey: Long,
)

@Serializable
data class WatchState(
    val items: List<WatchItem> = emptyList(),
    val targets: List<SavedTarget> = emptyList(),
    val parser: ParserConfig = ParserConfig(),
    val confirmSeconds: Int = 5,
    val snoozeA: Int = 15,
    val snoozeB: Int = 60,
    val updatedAt: Long = 0,
)

@Serializable
data class WatchCapture(
    val id: String,
    val text: String,
    val trigger: Trigger,
    val leadMinutes: Int = 0,
    val transcript: String? = null,
    val needsReview: Boolean = false,
    /** "post office" was said but not recognized, and the user then picked [learnTargetId]. */
    val learnAlias: String? = null,
    val learnTargetId: String? = null,
    val createdAt: Long,
)

/** What the phone understood from the watch's recording, for the watch's confirm screen. */
@Serializable
data class WatchDraft(
    val id: String,
    val task: String,
    val trigger: Trigger? = null,
    val leadMinutes: Int = 0,
    val unresolvedPhrase: String? = null,
    val unresolvedEdge: Edge? = null,
    val transcript: String = "",
) {
    val needsEdit: Boolean get() = unresolvedPhrase != null || task.isBlank()

    companion object {
        fun from(id: String, p: ParseResult) = WatchDraft(
            id, p.task, p.trigger, p.leadMinutes, p.unresolvedPhrase, p.unresolvedEdge, p.transcript,
        )
    }
}

@Serializable
data class WatchAction(
    val id: String,
    /** "done", "snooze" */
    val action: String,
    val minutes: Int = 0,
)
