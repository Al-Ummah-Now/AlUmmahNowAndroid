package net.alummahnow.core.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as Apple's EditDecisionListTests, so both editors behave alike. */
class EditDecisionListTest {
    private fun list(vararg durations: Long) =
        EditDecisionList(durations.mapIndexed { i, d -> EditClip("content://v/$i", d, id = "c$i") })

    @Test fun splitKeepsTotalAndSourceOffsets() {
        val split = list(10_000).split(4_000)
        assertEquals(10_000, split.durationMs)
        assertEquals(listOf(4_000L, 6_000L), split.clips.map { it.durationMs })
        assertEquals(4_000, split.clips[1].sourceStartMs)
        assertEquals("content://v/0", split.clips[1].sourceUri)
    }

    @Test fun splitIgnoresEdges() {
        val one = list(10_000)
        assertEquals(one, one.split(50))
        assertEquals(one, one.split(9_950))
        assertEquals(one, one.split(20_000))
    }

    @Test fun rippleDeleteClosesGap() {
        val edited = list(1_000, 2_000, 3_000).rippleDelete("c1")
        assertEquals(listOf("c0", "c2"), edited.clips.map { it.id })
        assertEquals(4_000, edited.durationMs)
        assertEquals(1_000, edited.startOf(1))
    }

    @Test fun moveClampsAtEnds() {
        val three = list(1, 2, 3)
        assertEquals(listOf("c1", "c0", "c2"), three.move("c1", -1).clips.map { it.id })
        assertEquals(listOf("c1", "c2", "c0"), three.move("c0", 9).clips.map { it.id })
        assertEquals(three, three.move("missing", 1))
    }

    @Test fun trimStaysInsideClip() {
        val trimmed = list(10_000).trim("c0", -500, 20_000)
        assertEquals(10_000, trimmed.clips[0].durationMs)
        val tight = list(10_000).trim("c0", 9_990, 9_995)
        assertEquals(EditDecisionList.MIN_CLIP_MS, tight.clips[0].durationMs)
        assertEquals(9_900, tight.clips[0].sourceStartMs)
    }

    @Test fun locateFindsClipAndOffset() {
        val three = list(1_000, 2_000, 3_000)
        assertEquals(1 to 500L, three.locate(1_500))
        assertNull(three.locate(6_000))
    }
}
