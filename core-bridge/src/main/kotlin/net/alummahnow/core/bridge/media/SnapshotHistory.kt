package net.alummahnow.core.bridge.media

/**
 * Undo and redo over whole snapshots, as Apple's `SnapshotHistory` (AUNStudio/Engines). Bounded, so a long session
 * never grows without limit; recording a new state clears the redo side. Immutable: every call returns a new history.
 */
data class SnapshotHistory<S>(
    val current: S,
    private val undoStack: List<S> = emptyList(),
    private val redoStack: List<S> = emptyList(),
    val limit: Int = DEFAULT_LIMIT
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Makes `state` current. A state equal to the current one is not an edit. */
    fun record(state: S): SnapshotHistory<S> {
        if (state == current) return this
        return copy(current = state, undoStack = (undoStack + current).takeLast(limit.coerceAtLeast(1)), redoStack = emptyList())
    }

    /** Replaces the current state without a new undo step (a drag in progress keeps one step). */
    fun amend(state: S): SnapshotHistory<S> = if (!canUndo) record(state) else copy(current = state, redoStack = emptyList())

    fun undo(): SnapshotHistory<S> {
        if (!canUndo) return this
        return copy(current = undoStack.last(), undoStack = undoStack.dropLast(1), redoStack = redoStack + current)
    }

    fun redo(): SnapshotHistory<S> {
        if (!canRedo) return this
        return copy(current = redoStack.last(), undoStack = undoStack + current, redoStack = redoStack.dropLast(1))
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
