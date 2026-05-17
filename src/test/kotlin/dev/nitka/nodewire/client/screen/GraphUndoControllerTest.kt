package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphUndoControllerTest {

    private val typeKey = ResourceLocation("nodewire", "constant")

    private fun graphWith(count: Int): NodeGraph =
        NodeGraph().apply {
            repeat(count) { i ->
                add(Node(
                    id = Node.newId(),
                    typeKey = typeKey,
                    pos = CanvasPos(i.toFloat() * 10f, 0f),
                    inputs = emptyList(),
                    outputs = emptyList(),
                ))
            }
        }

    @Test fun `undo with empty stack returns null`() {
        val c = GraphUndoController { 0L }
        assertNull(c.undo(graphWith(0)))
    }

    @Test fun `snapshot then undo returns previous state`() {
        val c = GraphUndoController { 0L }
        val v1 = graphWith(1)
        c.snapshot(v1, mergeable = false)
        val v2 = graphWith(2)
        val restored = c.undo(v2)
        assertNotNull(restored)
        assertEquals(1, restored!!.nodes.size)
    }

    @Test fun `redo replays after undo`() {
        val c = GraphUndoController { 0L }
        val v1 = graphWith(1)
        c.snapshot(v1, mergeable = false)
        val v2 = graphWith(2)
        val afterUndo = c.undo(v2)!!
        val afterRedo = c.redo(afterUndo)
        assertNotNull(afterRedo)
        assertEquals(2, afterRedo!!.nodes.size)
    }

    @Test fun `mergeable snapshots within window overwrite previous`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 100
        c.snapshot(graphWith(2), mergeable = true); now += 100
        c.snapshot(graphWith(3), mergeable = true)
        val restored = c.undo(graphWith(99))!!
        assertEquals(3, restored.nodes.size)
        assertNull(c.undo(restored))
    }

    @Test fun `non-mergeable push after mergeable breaks the merge`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 100
        c.snapshot(graphWith(2), mergeable = false); now += 100
        c.snapshot(graphWith(3), mergeable = true)
        assertEquals(3, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(2, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(1, c.undo(graphWith(99))!!.nodes.size)
        assertNull(c.undo(graphWith(99)))
    }

    @Test fun `mergeable snapshot beyond window starts a new entry`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 600
        c.snapshot(graphWith(2), mergeable = true)
        assertEquals(2, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(1, c.undo(graphWith(99))!!.nodes.size)
        assertNull(c.undo(graphWith(99)))
    }

    @Test fun `stack capped at 50 entries — oldest dropped`() {
        var now = 0L
        val c = GraphUndoController { now }
        repeat(60) { i ->
            c.snapshot(graphWith(i + 1), mergeable = false); now += 10
        }
        var popped = 0
        while (true) {
            val r = c.undo(graphWith(99)) ?: break
            popped++
            assertTrue(r.nodes.size >= 11)
        }
        assertEquals(50, popped)
    }

    @Test fun `redo cleared after fresh snapshot`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = false); now += 100
        c.snapshot(graphWith(2), mergeable = false); now += 100
        c.undo(graphWith(3))
        c.snapshot(graphWith(4), mergeable = false)
        assertNull(c.redo(graphWith(99)))
    }
}
