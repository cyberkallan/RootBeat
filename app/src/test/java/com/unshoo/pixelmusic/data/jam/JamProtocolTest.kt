package com.unshoo.pixelmusic.data.jam

import com.unshoo.pixelmusic.data.visualizer.VisualizerLevels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JamProtocolTest {
    @Test
    fun messageRoundTripKeepsTrackAndMembers() {
        val original = JamMessage(
            type = "state",
            code = "AB12CD",
            revision = 4,
            sentAtEpochMs = 1_700_000_000_000,
            positionMs = 12_500,
            isPlaying = true,
            track = JamTrack(
                id = "song-1",
                title = "Night Drive",
                artist = "RootBeat",
                album = "Sessions",
                durationMs = 180_000,
                streamUrl = "http://192.168.1.8:28765/audio/song-1?token=AB12CD",
            ),
            members = listOf(JamMember(id = "host", name = "Phone", isHost = true)),
            upNext = listOf(JamTrackRef(id = "song-2", title = "Afterglow", artist = "Lane")),
        )

        val decoded = JamCodec.decode(JamCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun rangeHeaderClampsToFileSize() {
        val (start, end, partial) = JamAudio.parseRange("bytes=100-5000", size = 1_000)

        assertTrue(partial)
        assertEquals(100L, start)
        assertEquals(999L, end)
    }

    @Test
    fun fftEnergyBecomesABarPeak() {
        val fft = ByteArray(16)
        fft[2] = 64
        fft[3] = 0

        val bars = VisualizerLevels.fftToBars(fft, barCount = 4)

        assertEquals(4, bars.size)
        assertTrue(bars[0] > 0.4f)
        assertEquals(0f, bars[3])
    }
}
