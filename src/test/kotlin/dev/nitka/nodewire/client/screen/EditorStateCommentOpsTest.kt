package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.NodeGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EditorStateCommentOpsTest {

    @Test fun addCommentAppendsToGraph() {
        val g = NodeGraph()
        val ed = EditorState(g)
        val id = ed.addComment(CanvasPos(10f, 20f))
        assertEquals(1, g.comments.size)
        assertEquals(id, g.comments[0].id)
        assertEquals("", g.comments[0].text)
    }

    @Test fun updateCommentTextWrites() {
        val g = NodeGraph()
        val ed = EditorState(g)
        val id = ed.addComment(CanvasPos.Zero)
        ed.updateCommentText(id, "hello\nworld")
        assertEquals("hello\nworld", g.comments[0].text)
    }

    @Test fun moveCommentShifts() {
        val g = NodeGraph()
        val ed = EditorState(g)
        val id = ed.addComment(CanvasPos(0f, 0f))
        ed.moveComment(id, 5f, 7f)
        assertEquals(5f, g.comments[0].pos.x)
        assertEquals(7f, g.comments[0].pos.y)
    }

    @Test fun resizeCommentClampsMinimum() {
        val g = NodeGraph()
        val ed = EditorState(g)
        val id = ed.addComment(CanvasPos.Zero)
        ed.resizeComment(id, 10, 10)
        assertEquals(60, g.comments[0].width)
        assertEquals(30, g.comments[0].height)
    }

    @Test fun removeCommentDeletes() {
        val g = NodeGraph()
        val ed = EditorState(g)
        val id = ed.addComment(CanvasPos.Zero)
        ed.removeComment(id)
        assertEquals(0, g.comments.size)
    }

    @Test fun snapshotIncludesComments() {
        val g = NodeGraph()
        val ed = EditorState(g)
        ed.addComment(CanvasPos(1f, 2f))
        val snap = ed.snapshotGraph()
        assertEquals(1, snap.comments.size)
    }
}
