package app.nudge.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records the watch mic (16 kHz mono PCM) until [stop], keeping every byte. Once [attach]
 * hands it the phone's audio channel, it streams everything recorded so far and keeps
 * streaming live, so the phone transcribes while you're still talking.
 */
class WatchRecorder(
    private val context: Context,
    /** Debug builds only: play this WAV instead of the mic, in real time. */
    private val testWav: File? = null,
) {
    companion object {
        const val RATE = 16_000
    }

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val pcm = ByteArrayOutputStream()
    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var sink: OutputStream? = null
    private val recorded = CompletableDeferred<ByteArray>()
    private val streamed = CompletableDeferred<Boolean>()

    val ok: Boolean get() = testWav != null ||
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread(name = "nudge-mic", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val fake = testWav?.takeIf { it.exists() }?.readBytes()?.let { it.copyOfRange(44, it.size) }
            var fakePos = 0
            val record = if (fake != null) null else try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE),
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (t: Throwable) { Log.e("NudgeMic", "AudioRecord", t); null }
            if (fake == null && record == null) {
                running = false
                recorded.complete(ByteArray(0))
                return@thread
            }
            record?.startRecording()
            val buf = ByteArray(RATE / 20 * 2) // 50 ms
            try {
                while (running) {
                    val n = if (record != null) record.read(buf, 0, buf.size) else {
                        Thread.sleep(50)
                        val take = minOf(buf.size, fake!!.size - fakePos).coerceAtLeast(0)
                        fake.copyInto(buf, 0, fakePos, fakePos + take)
                        buf.fill(0, take, buf.size)
                        fakePos += take
                        buf.size
                    }
                    if (n <= 0) continue
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < n) {
                        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort() / 32768.0
                        sum += v * v; i += 2
                    }
                    _level.value = (sqrt(sum / (n / 2)) * 8).toFloat().coerceIn(0f, 1f)
                    synchronized(lock) { pcm.write(buf, 0, n); lock.notifyAll() }
                }
            } finally {
                record?.stop(); record?.release()
                _level.value = 0f
                synchronized(lock) { lock.notifyAll() }
                recorded.complete(synchronized(lock) { pcm.toByteArray() })
            }
        }
    }

    /** Starts streaming to the phone: everything so far, then live. */
    fun attach(out: OutputStream) {
        if (sink != null) return
        sink = out
        thread(name = "nudge-send") {
            var sent = 0
            try {
                while (true) {
                    val chunk: ByteArray
                    synchronized(lock) {
                        while (running && pcm.size() == sent) lock.wait(200)
                        if (!running && pcm.size() == sent) return@thread finishStream(out, true)
                        val all = pcm.toByteArray()
                        chunk = all.copyOfRange(sent, all.size)
                    }
                    out.write(chunk)
                    out.flush()
                    sent += chunk.size
                }
            } catch (t: Throwable) {
                Log.w("NudgeMic", "stream to phone broke: ${t.message}")
                finishStream(out, false)
            }
        }
    }

    private fun finishStream(out: OutputStream, ok: Boolean) {
        runCatching { out.close() }
        streamed.complete(ok)
    }

    val isStreaming: Boolean get() = sink != null

    /** Stops recording. Returns all audio, after the phone has been sent the last of it. */
    suspend fun stop(): ByteArray {
        running = false
        val audio = recorded.await()
        if (sink != null) streamed.await()
        return audio
    }

    /** True if the live stream reached the end without breaking. */
    suspend fun streamedOk(): Boolean = sink != null && streamed.await()

    fun cancel() { running = false }
}
