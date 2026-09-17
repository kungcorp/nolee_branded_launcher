package ai.nolee.customlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16GainTest {
    @Test fun splitPcmSamplesMatchContiguousPlayback() {
        val pcm = byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0x00, 0x40)
        val expected = Pcm16Gain(5f).append(pcm)
        val gain = Pcm16Gain(5f)
        val actual = gain.append(pcm.copyOfRange(0, 1)) +
            gain.append(pcm.copyOfRange(1, 3)) +
            gain.append(pcm.copyOfRange(3, 5)) +
            gain.append(pcm.copyOfRange(5, 6))
        assertArrayEquals(expected, actual)
    }

    @Test fun clippingSaturatesWithoutWrapping() {
        val result = Pcm16Gain(5f).append(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), result)
    }

    @Test fun rawPcmIsUnchanged() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertArrayEquals(pcm, pcm16Payload(pcm))
    }

    @Test fun silenceIsNotSpeech() {
        org.junit.Assert.assertFalse(pcm16HasSpeech(ByteArray(16_000 * 2 * 8)))
    }

    @Test fun aShortBurstInALongTimeoutIsSpeech() {
        val pcm = ByteArray(16_000 * 2 * 8)
        var i = 16_000 * 2
        while (i < 16_000 * 2 + 16_000 / 5 * 2) {
            pcm[i] = 0x00
            pcm[i + 1] = 0x20
            i += 2
        }
        org.junit.Assert.assertTrue(pcm16HasSpeech(pcm))
    }

    @Test fun waveHeaderIsStripped() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24_000).putInt(48_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        assertArrayEquals(pcm, pcm16Payload(header.array() + pcm))
    }
}
