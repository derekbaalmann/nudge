package app.nudge.speech

import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * Collects 16 kHz mono 16-bit PCM arriving from elsewhere (the watch) and transcribes it
 * in one go when it ends.
 */
class PcmTranscriber(private val context: Context) {
    private val pcm = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        if (len > 0) pcm.write(bytes, 0, len)
    }

    /** End of audio. Null if the speech model isn't available. */
    fun finish(): String? = Asr.transcribe(context, Asr.pcmToFloats(pcm.toByteArray()))
}
