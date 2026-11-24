package net.alummahnow.feature.live

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiereClockTest {
    private fun reading(at: Long, playing: Boolean = true, keepUp: Boolean = true, speed: Float = 1f) =
        PlayerReading(at * 1000, playing, keepUp, speed)

    private fun PremiereFollower.at(moment: Long, reading: PlayerReading) = tick(moment * 1000, reading)

    @Test fun `is the server's time within the video`() {
        val start = Instant.ofEpochSecond(1000)
        val clock = PremiereClock(start, 20_000, 600_000)
        assertEquals(110_000, clock.position(start.plusSeconds(90)))
        assertEquals(0, clock.position(start.minusSeconds(60)))
        assertEquals(600_000, clock.position(start.plusSeconds(900)))
        assertTrue(clock.isOver(start.plusSeconds(580)))
        assertFalse(clock.isOver(start.plusSeconds(579)))
    }

    @Test fun `comes back when it drifts but not while loading`() {
        val f = PremiereFollower()
        assertEquals(emptyList<FollowAction>(), f.at(100, reading(99)))
        assertEquals(emptyList<FollowAction>(), f.at(105, reading(100, keepUp = false)))
        assertEquals(listOf(FollowAction.Seek(106_000)), f.at(106, reading(101)))
        assertFalse(f.isBehind)
    }

    @Test fun `nobody gets ahead or faster`() {
        val f = PremiereFollower()
        f.at(100, reading(100))
        assertEquals(listOf(FollowAction.Seek(101_000)), f.at(101, reading(300)))
        assertEquals(listOf(FollowAction.NormalSpeed), f.at(102, reading(102, speed = 2f)))
    }

    @Test fun `a viewer who goes back stays until they catch up or return`() {
        val f = PremiereFollower()
        f.at(100, reading(100))
        assertEquals(emptyList<FollowAction>(), f.at(101, reading(40)))
        assertTrue(f.isBehind)
        // Catching up may be faster
        assertEquals(emptyList<FollowAction>(), f.at(102, reading(42, speed = 2f)))
        assertEquals(listOf(FollowAction.NormalSpeed), f.at(103, reading(102, speed = 2f)))
        assertFalse(f.isBehind)
        f.at(104, reading(50))
        assertTrue(f.isBehind)
        f.returned()
        assertEquals(listOf(FollowAction.Seek(105_000)), f.at(105, reading(50)))
    }

    @Test fun `a viewer who passes the moment between two ticks is brought back`() {
        val f = PremiereFollower()
        f.at(100, reading(100))
        f.at(101, reading(90))
        assertTrue(f.isBehind)
        assertEquals(listOf(FollowAction.NormalSpeed, FollowAction.Seek(120_000)), f.at(120, reading(130, speed = 2f)))
        assertTrue(f.isFollowing)
    }

    @Test fun `a pause is the viewer's but not before it has played`() {
        val f = PremiereFollower()
        f.at(100, reading(0, playing = false))
        assertTrue(f.isFollowing)
        f.at(100, reading(100))
        f.at(101, reading(100, playing = false))
        f.at(110, reading(100, playing = false))
        assertTrue(f.isBehind)
        assertEquals(emptyList<FollowAction>(), f.at(111, reading(101)))
    }
}
