package app.nudge.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.nudge.core.LogEntry
import app.nudge.core.LogKind
import app.nudge.core.NudgeJson
import app.nudge.core.ParserConfig
import app.nudge.core.Reminder
import app.nudge.core.SavedTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class Settings(
    /** The two "Sleep" buttons on a reminder notification, in minutes. */
    val snoozeA: Int = 15,
    val snoozeB: Int = 60,
    /** Re-alert every this many minutes until Done or Sleep. */
    val nagMinutes: Int = 5,
    val nagByDefault: Boolean = true,
    /** The confirm card auto-saves after this many seconds. */
    val confirmSeconds: Int = 5,
    val reviewEnabled: Boolean = true,
    val reviewHour: Int = 20,
    val reviewMinute: Int = 0,
    val watchBuzz: Boolean = true,
    /** Keep the speech model loaded in a background service so recording is instant (~200 MB RAM). */
    val keepReady: Boolean = true,
    val parser: ParserConfig = ParserConfig(),
    val onboarded: Boolean = false,
)

/**
 * Everything lives in a few small JSON files in app storage. Reads come from memory;
 * writes update memory immediately and hit disk on a background thread.
 */
object Store {
    private const val TAG = "NudgeStore"
    private lateinit var dir: File
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nudge-store") }
    private val lock = Any()
    @Volatile private var loaded = false

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    private val _targets = MutableStateFlow<List<SavedTarget>>(emptyList())
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    private val _settings = MutableStateFlow(Settings())

    val reminders: StateFlow<List<Reminder>> = _reminders
    val targets: StateFlow<List<SavedTarget>> = _targets
    val log: StateFlow<List<LogEntry>> = _log
    val settings: StateFlow<Settings> = _settings

    private val reminderList = ListSerializer(Reminder.serializer())
    private val targetList = ListSerializer(SavedTarget.serializer())
    private val logList = ListSerializer(LogEntry.serializer())

    fun init(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            dir = context.applicationContext.filesDir
            _reminders.value = read("reminders.json", reminderList) ?: emptyList()
            _targets.value = read("targets.json", targetList) ?: emptyList()
            _log.value = read("log.json", logList) ?: emptyList()
            _settings.value = read("settings.json", Settings.serializer()) ?: Settings()
            loaded = true
        }
    }

    fun reminder(id: String): Reminder? = _reminders.value.firstOrNull { it.id == id }
    fun target(id: String): SavedTarget? = _targets.value.firstOrNull { it.id == id }

    fun upsert(r: Reminder) = editReminders { list ->
        val i = list.indexOfFirst { it.id == r.id }
        if (i < 0) list + r else list.toMutableList().also { it[i] = r }
    }

    fun removeReminder(id: String) = editReminders { list -> list.filterNot { it.id == id } }

    fun editReminders(f: (List<Reminder>) -> List<Reminder>) {
        synchronized(lock) { _reminders.value = f(_reminders.value) }
        save("reminders.json", reminderList) { _reminders.value }
    }

    fun upsertTarget(t: SavedTarget) {
        synchronized(lock) {
            val list = _targets.value
            val i = list.indexOfFirst { it.id == t.id }
            _targets.value = if (i < 0) list + t else list.toMutableList().also { it[i] = t }
        }
        save("targets.json", targetList) { _targets.value }
    }

    fun removeTarget(id: String) {
        synchronized(lock) { _targets.value = _targets.value.filterNot { it.id == id } }
        save("targets.json", targetList) { _targets.value }
    }

    fun updateSettings(f: (Settings) -> Settings) {
        synchronized(lock) { _settings.value = f(_settings.value) }
        save("settings.json", Settings.serializer()) { _settings.value }
    }

    fun log(r: Reminder, kind: LogKind, detail: String? = null) {
        synchronized(lock) {
            val entry = LogEntry(r.id, r.text, System.currentTimeMillis(), kind, detail)
            _log.value = (_log.value + entry).takeLast(3000)
        }
        save("log.json", logList) { _log.value }
    }

    /** Waits for pending writes; call before a receiver's goAsync() finishes. */
    fun flush() {
        try { io.submit {}.get(3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun <T> read(name: String, s: KSerializer<T>): T? = try {
        val f = File(dir, name)
        if (!f.exists()) null else NudgeJson.decodeFromString(s, AtomicFile(f).readFully().decodeToString())
    } catch (t: Throwable) {
        Log.e(TAG, "couldn't read $name", t)
        // Keep the unreadable file for recovery instead of overwriting it.
        File(dir, name).renameTo(File(dir, "$name.bad-${System.currentTimeMillis()}"))
        null
    }

    private fun <T> save(name: String, s: KSerializer<T>, current: () -> T) {
        io.execute {
            val f = AtomicFile(File(dir, name))
            val out = f.startWrite()
            try {
                out.write(NudgeJson.encodeToString(s, current()).encodeToByteArray())
                f.finishWrite(out)
            } catch (t: Throwable) {
                f.failWrite(out)
                Log.e(TAG, "couldn't write $name", t)
            }
        }
    }
}
