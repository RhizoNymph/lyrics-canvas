package dev.nymph.lyrics.logic.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A fake clock: samples every [POLL] ms from the time the feeder is at. */
private const val POLL = 50L
private const val QUIET = 100 // about -50 dBFS
private const val SPEECH = 8_000 // about -12 dBFS
private const val MURMUR = 400 // about -38 dBFS: between the silence and speech thresholds of a -50 floor

private class Feeder(var state: VadState = VadState.Initial, var now: Long = 0, val timeout: SilenceTimeout) {
    /** The time of the first sample that asked to stop, or null. */
    var stoppedAt: Long? = null

    fun feed(amplitude: Int, forMs: Long): Feeder {
        val end = now + forMs
        while (now < end) {
            val step = state.sample(now, amplitude, timeout)
            state = step.state
            if (step.stop && stoppedAt == null) stoppedAt = now
            now += POLL
        }
        return this
    }
}

class SilenceVadTest {
    @Test
    fun `amplitude to dBFS`() {
        assertEquals(0.0, amplitudeToDbfs(MAX_AMPLITUDE), 1e-9)
        assertEquals(0.0, amplitudeToDbfs(100_000), 1e-9)
        assertEquals(MIN_DBFS, amplitudeToDbfs(0))
        assertEquals(MIN_DBFS, amplitudeToDbfs(-5))
        assertEquals(-6.02, amplitudeToDbfs(MAX_AMPLITUDE / 2), 0.01)
    }

    @Test
    fun `level fraction spans -60 dBFS to full scale`() {
        assertEquals(0f, levelFraction(0))
        assertEquals(0f, levelFraction(10))
        assertEquals(1f, levelFraction(MAX_AMPLITUDE))
        assertEquals(0.5f, levelFraction(1_036), 0.01f) // about -30 dBFS
    }

    @Test
    fun `silence timeout choices`() {
        assertEquals(listOf(0, 2, 3, 5, 8, 10), SilenceTimeout.entries.map { it.seconds })
        assertEquals(SilenceTimeout.OFF, SilenceTimeout.Default)
        assertEquals(SilenceTimeout.S5, SilenceTimeout.fromSeconds(5))
        assertNull(SilenceTimeout.fromSeconds(4))
        assertEquals(3_000L, SilenceTimeout.S3.millis)
    }

    @Test
    fun `does not stop on silence before any speech was heard`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 10_000)
        assertNull(f.stoppedAt)
        assertFalse(f.state.armed)
    }

    @Test
    fun `stops after N seconds of continuous silence once speech was heard`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 1_000).feed(SPEECH, 1_000)
        assertTrue(f.state.armed)
        assertNull(f.stoppedAt)
        val silenceStart = f.now
        f.feed(QUIET, 3_000)
        assertEquals(silenceStart + 2_000, f.stoppedAt)
    }

    @Test
    fun `off never stops`() {
        val f = Feeder(timeout = SilenceTimeout.OFF).feed(QUIET, 1_000).feed(SPEECH, 1_000).feed(QUIET, 20_000)
        assertNull(f.stoppedAt)
        assertTrue(f.state.armed)
    }

    @Test
    fun `speech in the middle of a silence run restarts it`() {
        val f = Feeder(timeout = SilenceTimeout.S3).feed(QUIET, 500).feed(SPEECH, 500)
            .feed(QUIET, 2_000).feed(SPEECH, 300)
        val restart = f.now
        f.feed(QUIET, 5_000)
        assertEquals(restart + 3_000, f.stoppedAt)
    }

    @Test
    fun `a short blip does not break the silence run but a sustained murmur does`() {
        val blip = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 500).feed(SPEECH, 500)
        val silenceStart = blip.now
        blip.feed(QUIET, 1_000).feed(MURMUR, 100).feed(QUIET, 2_000)
        assertEquals(silenceStart + 2_000, blip.stoppedAt)

        val murmur = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 500).feed(SPEECH, 500).feed(QUIET, 1_000).feed(MURMUR, 400)
        val again = murmur.now
        murmur.feed(QUIET, 3_000)
        assertNotNull(murmur.stoppedAt)
        assertTrue(murmur.stoppedAt!! >= again + 2_000 - POLL * 4, "stopped at ${murmur.stoppedAt}, run restarted near $again")
    }

    @Test
    fun `a single loud click does not arm`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 500).feed(SPEECH, POLL).feed(QUIET, 5_000)
        assertFalse(f.state.armed)
        assertNull(f.stoppedAt)
    }

    @Test
    fun `the noise floor follows a louder background slowly and a quieter one quickly`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 1_000)
        val quietFloor = f.state.floorDb!!
        assertEquals(amplitudeToDbfs(QUIET), quietFloor, 0.5)
        f.feed(MURMUR, 1_000) // +12 dB background: rises about 2 dB/s, not at once
        val risen = f.state.floorDb!!
        assertTrue(risen > quietFloor && risen < quietFloor + 4, "floor $risen")
        f.feed(QUIET, 500)
        assertEquals(quietFloor, f.state.floorDb!!, 1.0)
    }

    @Test
    fun `the floor does not rise during speech`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 1_000)
        val before = f.state.floorDb!!
        f.feed(SPEECH, 10_000)
        assertEquals(before, f.state.floorDb!!, 1e-9)
    }

    @Test
    fun `a background that rises over the take still lets silence stop it`() {
        // Fan switched on mid-take: the floor creeps up to it, so the louder background becomes silence again.
        val f = Feeder(timeout = SilenceTimeout.S2).feed(QUIET, 500).feed(SPEECH, 500).feed(MURMUR, 15_000)
        assertNotNull(f.stoppedAt)
    }

    @Test
    fun `a dead-quiet input keeps a usable floor`() {
        val f = Feeder(timeout = SilenceTimeout.S2).feed(0, 1_000)
        assertEquals(VadConfig.Default.minFloorDb, f.state.floorDb!!, 1e-9)
        f.feed(SPEECH, 500).feed(0, 3_000)
        assertNotNull(f.stoppedAt)
    }
}
