package dev.nitka.nodewire.script.lexer

import dev.nitka.nodewire.ui.render.HlKind
import dev.nitka.nodewire.ui.render.HlSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptHighlighterTest {

    private fun spanOf(src: String, sub: String): HlSpan? {
        val start = src.indexOf(sub)
        return ScriptHighlighter.highlight(src).firstOrNull { it.start == start && it.end == start + sub.length }
    }

    @Test
    fun `keyword vs dsl classification`() {
        val src = "val x input output Redstone"
        // `val` is a keyword.
        assertEquals(HlKind.KEYWORD, spanOf(src, "val")?.kind)
        // `input`, `output`, `Redstone` are DSL words.
        assertEquals(HlKind.DSL, spanOf(src, "input")?.kind)
        assertEquals(HlKind.DSL, spanOf(src, "output")?.kind)
        assertEquals(HlKind.DSL, spanOf(src, "Redstone")?.kind)
        // `x` is a plain identifier — no span emitted for it.
        assertTrue(ScriptHighlighter.highlight(src).none { src.substring(it.start, it.end) == "x" })
    }

    @Test
    fun `spans are ordered and non-overlapping`() {
        val spans = ScriptHighlighter.highlight("val a = 12 // hi\noutput")
        for (i in 1 until spans.size) {
            assertTrue(spans[i - 1].end <= spans[i].start, "spans must be ordered & non-overlapping")
        }
    }

    @Test
    fun `string literal with escape is one span`() {
        val src = "x = \"a\\\"b\" + 1"
        val span = ScriptHighlighter.highlight(src).first { it.kind == HlKind.STRING }
        // The escaped quote must NOT end the string early: span covers "a\"b" incl. quotes.
        assertEquals(src.indexOf('"'), span.start)
        assertEquals(src.indexOf('"') + "\"a\\\"b\"".length, span.end)
        assertEquals("\"a\\\"b\"", src.substring(span.start, span.end))
    }

    @Test
    fun `number literal`() {
        val span = spanOf("y = 42", "42")
        assertEquals(HlKind.NUMBER, span?.kind)
    }

    @Test
    fun `mid-line comment swallows rest of line`() {
        val src = "val a = 1 // val output 99\nval b"
        val comment = ScriptHighlighter.highlight(src).first { it.kind == HlKind.COMMENT }
        assertEquals(src.indexOf("//"), comment.start)
        // Comment runs to (not past) the newline.
        assertEquals(src.indexOf('\n'), comment.end)
        assertEquals("// val output 99", src.substring(comment.start, comment.end))
        // Nothing inside the comment is classified as keyword/dsl/number.
        val inside = ScriptHighlighter.highlight(src)
            .filter { it.start in comment.start until comment.end && it.kind != HlKind.COMMENT }
        assertTrue(inside.isEmpty(), "no tokens recognized inside a comment")
        // But the SECOND line's `val` (after the newline) is still a keyword.
        val secondVal = src.indexOf("val", comment.end)
        val secondValSpan = ScriptHighlighter.highlight(src)
            .first { it.start == secondVal && it.end == secondVal + 3 }
        assertEquals(HlKind.KEYWORD, secondValSpan.kind)
    }
}
