package ai.nolee.brandedlauncher

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * If Qwen prefixes the first audio burst with a WAV container, return the PCM payload.
 * Raw 24 kHz PCM is passed through unchanged.
 */
internal fun pcm16Payload(bytes: ByteArray): ByteArray {
    if (bytes.size < 12) return bytes
    if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return bytes
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val id = ascii(bytes, offset, 4)
        val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (size < 0) return bytes
        offset += 8
        if (id == "data") return bytes.copyOfRange(offset, bytes.size)
        val skip = size + (size and 1)
        if (offset + skip > bytes.size) return bytes
        offset += skip
    }
    return bytes
}

/** Amplifies streaming little-endian PCM16 without losing a sample split across chunks. */
internal class Pcm16Gain(private val gain: Float) {
    private var carry: Byte? = null

    fun append(bytes: ByteArray): ByteArray {
        val merged = ByteArray(bytes.size + if (carry == null) 0 else 1)
        var offset = 0
        carry?.let { merged[offset++] = it }
        bytes.copyInto(merged, offset)
        val even = merged.size - merged.size % 2
        carry = if (even == merged.size) null else merged.last()
        val out = ByteArray(even)
        var i = 0
        while (i < even) {
            val sample = (merged[i + 1].toInt() shl 8) or (merged[i].toInt() and 255)
            val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
            out[i] = scaled.toByte()
            out[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return out
    }
}

/**
 * True when [pcm] contains a burst of speech, not just the mean of a long quiet timeout.
 * A 200 ms window above the noise floor, or a sharp peak, counts; padded silence does not.
 */
internal fun pcm16HasSpeech(pcm: ByteArray, sampleRate: Int = 16_000): Boolean {
    if (pcm.size < 4) return false
    val window = (sampleRate / 5).coerceAtLeast(1)
    var acc = 0L
    var n = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = kotlin.math.abs(
            ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt(),
        )
        acc += sample
        n++
        if (n >= window) {
            if (acc / n >= SPEECH_WINDOW) return true
            acc = 0
            n = 0
        }
        i += 2
    }
    // An isolated click used to count as speech regardless of the window's energy.
    return n >= sampleRate / 20 && acc / n >= SPEECH_WINDOW
}

private const val SPEECH_WINDOW = 600

private fun ascii(bytes: ByteArray, offset: Int, count: Int) =
    String(bytes, offset, count, Charsets.US_ASCII)
