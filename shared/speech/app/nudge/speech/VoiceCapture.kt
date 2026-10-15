package app.nudge.speech

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
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records the mic until [stop], then transcribes the whole recording once with [Asr].
 * Only a loudness level is exposed while recording (for the waveform), never partial text.
 */
class VoiceCapture(
    private val context: Context,
    /** Debug builds only: play this 16 kHz mono WAV instead of the mic, in real time. */
    private val testWav: java.io.File? = null,
) {
    /** 0..1 loudness, updated every 50 ms while recording. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var running = false
    @Volatile private var cancelled = false
    private val result = CompletableDeferred<String>()

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (testWav == null && !hasPermission()) { _error.value = "Microphone permission needed"; result.complete(""); return }
        running = true
        thread(name = "nudge-mic", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val fake = testWav?.takeIf { it.exists() }?.readBytes()?.let { b ->
                val pcm = java.nio.ByteBuffer.wrap(b, 44, b.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(pcm.remaining()).also { pcm.get(it) }
            }
            var fakePos = 0
            val record = if (fake != null) null else try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, Asr.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(
                        AudioRecord.getMinBufferSize(Asr.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                        Asr.SAMPLE_RATE,
                    ),
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (t: Throwable) {
                Log.e("NudgeMic", "AudioRecord failed", t); null
            }
            if (fake == null && record == null) {
                _error.value = "Couldn't open the microphone"
                running = false
                result.complete("")
                return@thread
            }
            record?.startRecording()

            val chunk = ShortArray(Asr.SAMPLE_RATE / 20) // 50 ms
            val all = ArrayList<FloatArray>()
            try {
                while (running) {
                    val n = if (record != null) record.read(chunk, 0, chunk.size) else {
                        Thread.sleep(50)
                        val take = minOf(chunk.size, fake!!.size - fakePos).coerceAtLeast(0)
                        fake.copyInto(chunk, 0, fakePos, fakePos + take)
                        chunk.fill(0, take, chunk.size)
                        fakePos += take
                        chunk.size
                    }
                    if (n <= 0) continue
                    val f = FloatArray(n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768f
                        f[i] = v; sum += v * v
                    }
                    all += f
                    _level.value = (sqrt(sum / n) * 8).toFloat().coerceIn(0f, 1f)
                }
            } finally {
                record?.stop()
                record?.release()
                _level.value = 0f
            }
            if (cancelled) { result.complete(""); return@thread }

            val samples = FloatArray(all.sumOf { it.size })
            var o = 0
            all.forEach { it.copyInto(samples, o); o += it.size }
            val text = Asr.transcribe(context, samples)
            if (text == null) _error.value = "Speech model isn't installed. Run tools/install.ps1 on the PC."
            result.complete(text ?: "")
        }
    }

    /** Stops recording and returns what was said (empty if nothing / failed; see [error]). */
    suspend fun stop(): String {
        running = false
        return result.await()
    }

    /** Stops without transcribing. */
    fun cancel() { cancelled = true; running = false }
}
