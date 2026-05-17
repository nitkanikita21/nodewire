package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph

/**
 * Snapshot-based undo/redo with a 500ms merge window for continuous
 * operations (drag, slider). Capped at 50 entries — oldest entries
 * dropped silently. Pure data; no Compose state, no MC dependency.
 *
 * Caller passes the current graph each call; controller never holds a
 * reference to the live graph, only to its deep copies.
 */
class GraphUndoController(private val nowMs: () -> Long) {

    private val undoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private val redoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private var lastPushAt: Long = 0L
    private var lastMergeable: Boolean = false
    private val mergeWindowMs: Long = 500L
    private val cap: Int = 50

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun snapshot(pre: NodeGraph, mergeable: Boolean) {
        val now = nowMs()
        val canMerge = mergeable && lastMergeable && (now - lastPushAt) < mergeWindowMs
        val copy = pre.deepCopy()
        if (canMerge && undoStack.isNotEmpty()) {
            undoStack[undoStack.size - 1] = copy
        } else {
            undoStack.addLast(copy)
            if (undoStack.size > cap) undoStack.removeFirst()
        }
        lastPushAt = now
        lastMergeable = mergeable
        redoStack.clear()
    }

    fun undo(current: NodeGraph): NodeGraph? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current.deepCopy())
        lastMergeable = false
        return undoStack.removeLast()
    }

    fun redo(current: NodeGraph): NodeGraph? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current.deepCopy())
        lastMergeable = false
        return redoStack.removeLast()
    }
}
