package app.nudge.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * Offline speech recognition: NVIDIA Parakeet TDT 0.6B (via sherpa-onnx), run once over the
 * whole recording after you stop talking.
 *
 * The model (~630 MB) is too big for the APK, so tools/install.ps1 copies it to
 * <external files>/models/parakeet-0.6b/. The helper service keeps it loaded so results are quick.
 */
object Asr {
    private const val TAG = "NudgeAsr"
    const val SAMPLE_RATE = 16_000
    const val MODEL = "parakeet-0.6b"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loading = false
    private val lock = Object()

    fun modelDir(context: Context) = File(context.getExternalFilesDir(null), "models/$MODEL")

    fun installed(context: Context) = File(modelDir(context), "encoder.int8.onnx").exists()

    val isLoaded: Boolean get() = recognizer != null

    /** Starts loading the model in the background, if it isn't already. */
    fun warmUp(context: Context) {
        if (recognizer != null || loading) return
        synchronized(lock) {
            if (recognizer != null || loading) return
            loading = true
        }
        val app = context.applicationContext
        thread(name = "asr-load", priority = Thread.MAX_PRIORITY) {
            try { load(app) } finally { synchronized(lock) { loading = false; lock.notifyAll() } }
        }
    }

    private fun load(context: Context) {
        if (!installed(context)) { Log.w(TAG, "model not installed at ${modelDir(context)}"); return }
        val d = modelDir(context)
        val t0 = System.currentTimeMillis()
        try {
            recognizer = OfflineRecognizer(
                null,
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$d/encoder.int8.onnx",
                            decoder = "$d/decoder.int8.onnx",
                            joiner = "$d/joiner.int8.onnx",
                        ),
                        tokens = "$d/tokens.txt",
                        numThreads = 4,
                        modelType = "nemo_transducer",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
            Log.i(TAG, "model ready in ${System.currentTimeMillis() - t0} ms")
        } catch (t: Throwable) {
            Log.e(TAG, "model failed to load", t)
        }
    }

    /**
     * Transcribes a whole recording (16 kHz mono floats). Waits for a load in progress.
     * Returns null if the model isn't installed or couldn't load.
     */
    fun transcribe(context: Context, samples: FloatArray, waitMs: Long = 30_000): String? {
        warmUp(context)
        synchronized(lock) {
            val until = System.currentTimeMillis() + waitMs
            while (recognizer == null && loading && System.currentTimeMillis() < until) {
                lock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
            }
        }
        val r = recognizer ?: return null
        if (samples.isEmpty()) return ""
        val t0 = System.currentTimeMillis()
        // A little silence on both ends helps the first and last words.
        val pad = FloatArray(SAMPLE_RATE / 4)
        val s = r.createStream()
        return try {
            s.acceptWaveform(pad + samples + pad, SAMPLE_RATE)
            r.decode(s)
            r.getResult(s).text.trim().also {
                Log.i(TAG, "${samples.size / 16} ms of audio in ${System.currentTimeMillis() - t0} ms: \"$it\"")
            }
        } finally {
            s.release()
        }
    }

    /** 16-bit little-endian PCM bytes to floats. */
    fun pcmToFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            out[i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() / 32768f
        }
        return out
    }
}
