package net.alummahnow.core.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same behaviour as Apple's SnapshotHistory. */
class SnapshotHistoryTest {
    @Test fun undoAndRedoWalkTheSnapshots() {
        val h = SnapshotHistory(0).record(1).record(2)
        assertEquals(1, h.undo().current)
        assertEquals(0, h.undo().undo().current)
        assertEquals(2, h.undo().redo().current)
        assertFalse(h.undo().undo().canUndo)
    }

    @Test fun recordingClearsRedoAndEqualStatesAreNotEdits() {
        val h = SnapshotHistory(0).record(1).undo().record(5)
        assertFalse(h.canRedo)
        assertEquals(h, h.record(5))
    }

    @Test fun amendKeepsOneStep() {
        val h = SnapshotHistory(0).record(1).amend(2).amend(3)
        assertEquals(3, h.current)
        assertEquals(0, h.undo().current)
        assertTrue(SnapshotHistory(0).amend(1).canUndo)
    }

    @Test fun isBounded() {
        var h = SnapshotHistory(0, limit = 3)
        for (i in 1..10) h = h.record(i)
        assertEquals(7, h.undo().undo().undo().current)
        assertFalse(h.undo().undo().undo().canUndo)
    }
}
