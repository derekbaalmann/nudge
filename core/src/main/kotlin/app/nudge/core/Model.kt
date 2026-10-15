package app.nudge.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Arriving / connecting / plugging in is [ENTER]; leaving / disconnecting / unplugging is [EXIT]. */
@Serializable
enum class Edge { ENTER, EXIT }

@Serializable
enum class TargetKind { PLACE, WIFI, BLUETOOTH }

/**
 * Something a reminder can be tied to: a saved place, a wifi network or a bluetooth device.
 * [aliases] are the extra names you might say for it ("van", "work truck" for a car stereo called SYNC).
 */
@Serializable
data class SavedTarget(
    val id: String,
    val kind: TargetKind,
    val label: String,
    /** SSID for wifi, device name for bluetooth, unused for places. */
    val value: String = "",
    /** Bluetooth MAC, when known. Matching prefers it over the name. */
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Float = 150f,
    val aliases: List<String> = emptyList(),
) {
    val spokenNames: List<String> get() = listOf(label) + aliases
}

@Serializable
enum class RepeatUnit { MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

@Serializable
data class RepeatRule(
    val unit: RepeatUnit,
    val interval: Int = 1,
    /** ISO day numbers, 1 = Monday … 7 = Sunday. Only for [RepeatUnit.WEEK]; empty = the start day's weekday. */
    val daysOfWeek: List<Int> = emptyList(),
    /** 1–31, or -1 for the last day. Only for [RepeatUnit.MONTH]; null = the start date's day. */
    val dayOfMonth: Int? = null,
)

@Serializable
sealed interface Trigger {
    @Serializable
    @SerialName("at")
    data class At(val epochMillis: Long) : Trigger

    @Serializable
    @SerialName("repeat")
    data class Repeat(
        val rule: RepeatRule,
        val hour: Int,
        val minute: Int,
        /** Epoch day the series counts from (used for intervals, weekday and month-day defaults). */
        val startEpochDay: Long,
    ) : Trigger

    @Serializable
    @SerialName("target")
    data class Target(
        val targetId: String,
        val label: String,
        val kind: TargetKind,
        val edge: Edge,
    ) : Trigger

    /** ENTER = plugged in to charge, EXIT = unplugged. */
    @Serializable
    @SerialName("power")
    data class Power(val edge: Edge) : Trigger

    /** No trigger: lives in the Inbox until the daily review. */
    @Serializable
    @SerialName("none")
    data object None : Trigger
}

@Serializable
enum class Status {
    /** Waiting for its trigger (or in the Inbox when the trigger is None). */
    WAITING,

    /** Notification is showing; nagging until Done or Sleep. */
    FIRING,

    /** Put off with a Sleep button or snooze-to-context. */
    SNOOZED,

    DONE,
}

@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val trigger: Trigger,
    val createdAt: Long,
    val status: Status = Status.WAITING,
    /** What was actually heard, for the history screen. */
    val transcript: String? = null,
    /** Next time-based wake-up: the trigger time, a snooze end, or the next nag. */
    val nextFireAt: Long? = null,
    /** When snoozed "until I get home" etc., the context that wakes it back up. */
    val snoozeTrigger: Trigger? = null,
    /** Heads-up this many minutes before a timed reminder. */
    val leadMinutes: Int = 0,
    val leadFired: Boolean = false,
    /** Keep re-alerting every few minutes until Done or Sleep. */
    val nag: Boolean = true,
    val lastFiredAt: Long? = null,
    val fireCount: Int = 0,
    val doneAt: Long? = null,
    /** Saved from a failed parse or abandoned edit; shown with a "needs a trigger" badge. */
    val needsReview: Boolean = false,
) {
    val isInbox: Boolean get() = trigger == Trigger.None && status == Status.WAITING
    val isRepeating: Boolean get() = trigger is Trigger.Repeat
}

@Serializable
enum class LogKind { CREATED, FIRED, SNOOZED, DONE, DELETED, EDITED }

@Serializable
data class LogEntry(
    val reminderId: String,
    val text: String,
    val at: Long,
    val kind: LogKind,
    val detail: String? = null,
)

val NudgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}
