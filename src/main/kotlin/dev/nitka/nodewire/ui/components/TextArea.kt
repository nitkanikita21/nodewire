package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.KeyHandler
import dev.nitka.nodewire.ui.input.LocalKeyFocus
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.BackgroundModifier
import dev.nitka.nodewire.ui.modifier.style.BorderModifier
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.HlSpan
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

/** Indent unit inserted on Tab (and the block indent/dedent step). */
private const val INDENT = "    "
private const val INDENT_WIDTH = 4

/**
 * Multi-line text editor with the editing features a code editor needs:
 *   * **Selection** — Shift+arrows/Home/End, Ctrl+A, and mouse drag (Shift+click
 *     extends). Selected text is highlighted and replaced on type/paste.
 *   * **Clipboard** — Ctrl+C / Ctrl+X / Ctrl+V (via Minecraft's clipboard).
 *   * **Tab** — inserts [INDENT]; with a multi-line selection, Tab indents and
 *     Shift+Tab dedents the whole block.
 *   * **Word navigation** — Ctrl+Left/Right and Ctrl+Backspace.
 *   * `Enter` inserts a newline; `Ctrl+Enter` / `Esc` release focus.
 *
 * Externally driven via [value]; internal edits mirror back through [onValueChange].
 */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    highlight: ((String) -> List<HlSpan>)? = null,
) {
    val focusController = LocalKeyFocus.current
    val font = Minecraft.getInstance().font
    val keyboard = Minecraft.getInstance().keyboardHandler

    // text + caret (offset into text) + selection anchor (null = no selection).
    var text by remember { mutableStateOf(value) }
    var caret by remember { mutableStateOf(value.length) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    val cb = rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (value != text) {
            text = value
            caret = caret.coerceAtMost(text.length)
            anchor = null
        }
    }

    val textScale = NwTheme.typography.caption.scale
    val lineHeightPx = (font.lineHeight * textScale).toInt().coerceAtLeast(1)
    fun fontWidth(s: String): Int = (font.width(s) * textScale).toInt()

    // ── line/offset helpers ─────────────────────────────────────────────
    fun lineColOf(offset: Int): Pair<Int, Int> {
        var lineIdx = 0; var lineStart = 0
        for (i in 0 until offset.coerceIn(0, text.length)) {
            if (text[i] == '\n') { lineIdx++; lineStart = i + 1 }
        }
        return lineIdx to (offset - lineStart)
    }
    fun lineOf(offset: Int): Int = lineColOf(offset).first
    fun offsetOfLineStart(lineIdx: Int): Int {
        if (lineIdx <= 0) return 0
        var seen = 0
        for (i in text.indices) if (text[i] == '\n') { seen++; if (seen == lineIdx) return i + 1 }
        return text.length
    }
    fun lineLength(lineIdx: Int): Int {
        val start = offsetOfLineStart(lineIdx)
        var end = start
        while (end < text.length && text[end] != '\n') end++
        return end - start
    }

    // ── selection helpers ───────────────────────────────────────────────
    fun hasSelection(): Boolean = anchor.let { it != null && it != caret }
    fun selMin(): Int = minOf(anchor ?: caret, caret)
    fun selMax(): Int = maxOf(anchor ?: caret, caret)
    fun selectedText(): String = if (hasSelection()) text.substring(selMin(), selMax()) else ""

    /** Commit text + caret; clears selection. Single source of simple edits. */
    fun apply(newText: String, newCaret: Int) {
        text = newText
        caret = newCaret.coerceIn(0, newText.length)
        anchor = null
        cb.value(newText)
    }

    fun deleteSelectionIfAny(): Boolean {
        if (!hasSelection()) { anchor = null; return false }
        val lo = selMin(); val hi = selMax()
        apply(text.substring(0, lo) + text.substring(hi), lo)
        return true
    }

    fun insertString(s: String) {
        val lo = if (hasSelection()) selMin() else caret
        val hi = if (hasSelection()) selMax() else caret
        apply(text.substring(0, lo) + s + text.substring(hi), lo + s.length)
    }

    fun backspace() {
        if (deleteSelectionIfAny()) return
        if (caret == 0) return
        apply(text.substring(0, caret - 1) + text.substring(caret), caret - 1)
    }
    fun deleteForward() {
        if (deleteSelectionIfAny()) return
        if (caret >= text.length) return
        apply(text.substring(0, caret) + text.substring(caret + 1), caret)
    }

    // ── caret movement (extend = hold shift to grow the selection) ───────
    fun place(newCaret: Int, extend: Boolean) {
        if (extend) { if (anchor == null) anchor = caret } else anchor = null
        caret = newCaret.coerceIn(0, text.length)
    }
    fun moveUpPos(): Int {
        val (line, col) = lineColOf(caret)
        if (line == 0) return 0
        return offsetOfLineStart(line - 1) + col.coerceAtMost(lineLength(line - 1))
    }
    fun moveDownPos(): Int {
        val (line, col) = lineColOf(caret)
        if (line >= text.count { it == '\n' }) return text.length
        return offsetOfLineStart(line + 1) + col.coerceAtMost(lineLength(line + 1))
    }
    fun homePos(): Int = offsetOfLineStart(lineOf(caret))
    fun endPos(): Int { val l = lineOf(caret); return offsetOfLineStart(l) + lineLength(l) }
    fun wordLeftOf(from: Int): Int {
        var i = from
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }
    fun wordRightOf(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    // ── clipboard ────────────────────────────────────────────────────────
    fun copy() { val s = selectedText(); if (s.isNotEmpty()) keyboard.clipboard = s }
    fun cut() { if (hasSelection()) { keyboard.clipboard = selectedText(); deleteSelectionIfAny() } }
    fun paste() {
        val c = keyboard.clipboard ?: return
        if (c.isNotEmpty()) insertString(c.replace("\r\n", "\n").replace('\r', '\n'))
    }
    fun selectAll() { anchor = 0; caret = text.length }

    // ── block indent / dedent ────────────────────────────────────────────
    fun reindent(dedent: Boolean) {
        val firstLine = lineOf(if (hasSelection()) selMin() else caret)
        var lastLine = lineOf(if (hasSelection()) selMax() else caret)
        // a selection ending exactly at a line start shouldn't pull in that next line
        if (hasSelection() && selMax() == offsetOfLineStart(lastLine) && lastLine > firstLine) lastLine--

        val lines = text.split('\n').toMutableList()
        val shift = IntArray(lines.size) // chars added (+) / removed (-) at each line start
        for (i in firstLine..lastLine) {
            if (dedent) {
                var rm = 0
                val ln = lines[i]
                while (rm < INDENT_WIDTH && rm < ln.length && ln[rm] == ' ') rm++
                lines[i] = ln.substring(rm); shift[i] = -rm
            } else {
                lines[i] = INDENT + lines[i]; shift[i] = INDENT.length
            }
        }
        val newText = lines.joinToString("\n")
        fun newLineStart(li: Int): Int { var s = 0; for (k in 0 until li) s += lines[k].length + 1; return s }
        fun remap(p: Int): Int {
            val (l, col) = lineColOf(p)
            val newCol = (col + shift[l]).coerceIn(0, lines[l].length)
            return newLineStart(l) + newCol
        }
        val nc = remap(caret); val na = anchor?.let { remap(it) }
        text = newText; cb.value(newText); caret = nc; anchor = na
    }

    // ── caret-from-(x,y) ─────────────────────────────────────────────────
    fun offsetAt(localX: Int, localY: Int): Int {
        val lineIdx = (localY / lineHeightPx).coerceAtLeast(0)
        val totalLines = text.count { it == '\n' } + 1
        val clampedLine = lineIdx.coerceAtMost(totalLines - 1)
        val lineStart = offsetOfLineStart(clampedLine)
        val lineText = text.substring(lineStart, lineStart + lineLength(clampedLine))
        var lo = 0; var hi = lineText.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fontWidth(lineText.substring(0, mid)) <= localX) lo = mid else hi = mid - 1
        }
        return lineStart + lo
    }

    val handler = remember {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean {
                if (!enabled) return false
                return when (event) {
                    is KeyEvent.Char -> {
                        val blocked = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER
                        if (event.modifiers and blocked != 0) false
                        else { insertString(Character.toChars(event.codePoint).concatToString()); true }
                    }
                    is KeyEvent.Press -> {
                        val ctrl = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0
                        val shift = event.modifiers and GLFW.GLFW_MOD_SHIFT != 0
                        when (event.keyCode) {
                            // clipboard / select-all
                            GLFW.GLFW_KEY_A -> if (ctrl) { selectAll(); true } else false
                            GLFW.GLFW_KEY_C -> if (ctrl) { copy(); true } else false
                            GLFW.GLFW_KEY_X -> if (ctrl) { cut(); true } else false
                            GLFW.GLFW_KEY_V -> if (ctrl) { paste(); true } else false
                            // indentation
                            GLFW.GLFW_KEY_TAB -> {
                                if (shift) reindent(dedent = true)
                                else if (hasSelection() && lineOf(selMin()) != lineOf(selMax())) reindent(dedent = false)
                                else insertString(INDENT)
                                true
                            }
                            // editing
                            InputConstants.KEY_BACKSPACE -> {
                                if (ctrl && !hasSelection() && caret > 0) {
                                    val w = wordLeftOf(caret); apply(text.substring(0, w) + text.substring(caret), w)
                                } else backspace()
                                true
                            }
                            InputConstants.KEY_DELETE -> {
                                if (ctrl && !hasSelection() && caret < text.length) {
                                    val w = wordRightOf(caret); apply(text.substring(0, caret) + text.substring(w), caret)
                                } else deleteForward()
                                true
                            }
                            // movement (shift extends selection)
                            InputConstants.KEY_LEFT -> { place(if (ctrl) wordLeftOf(caret) else caret - 1, shift); true }
                            InputConstants.KEY_RIGHT -> { place(if (ctrl) wordRightOf(caret) else caret + 1, shift); true }
                            InputConstants.KEY_UP -> { place(if (ctrl) 0 else moveUpPos(), shift); true }
                            InputConstants.KEY_DOWN -> { place(if (ctrl) text.length else moveDownPos(), shift); true }
                            InputConstants.KEY_HOME -> { place(if (ctrl) 0 else homePos(), shift); true }
                            InputConstants.KEY_END -> { place(if (ctrl) text.length else endPos(), shift); true }
                            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                                if (ctrl) focusController?.release(this) else insertString("\n"); true
                            }
                            InputConstants.KEY_ESCAPE -> { focusController?.release(this); true }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
        }
    }
    val focused = focusController?.isFocused(handler) == true
    DisposableEffect(handler) { onDispose { focusController?.release(handler) } }

    var blinkTick by remember { mutableStateOf(0) }
    LaunchedEffect(focused) { if (focused) while (true) { delay(50); blinkTick++ } }
    val blinkOn = ((Util.getMillis() % 1000L) < 530L)
    @Suppress("UNUSED_EXPRESSION") blinkTick

    val bg = if (focused) NwTheme.colors.surfacePressed else NwTheme.colors.surfaceHover
    val selectionColor = NwTheme.colors.accent.copy(alpha = 0.30f)

    Layout(
        modifier = modifier
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2)
            .onHover { }
            .pointerInput { ev, localX, localY ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        if (Screen.hasShiftDown()) place(offsetAt(localX, localY), extend = true)
                        else { caret = offsetAt(localX, localY); anchor = caret } // empty sel; drag will grow it
                        true
                    }
                    is PointerEvent.Drag -> { caret = offsetAt(localX, localY); true } // anchor stays → selects
                    else -> false
                }
            },
        renderer = TextAreaRenderer(
            textProvider = { text },
            caretProvider = { caret },
            selectionProvider = { if (hasSelection()) (selMin() until selMax()) else null },
            font = font,
            textColor = NwTheme.colors.onSurface,
            placeholderColor = NwTheme.colors.onSurfaceDisabled,
            caretColor = NwTheme.colors.accent,
            selectionColor = selectionColor,
            placeholder = placeholder,
            isFocused = { focused },
            blinkOn = { blinkOn },
            textScale = textScale,
            lineHeightPx = lineHeightPx,
            fontWidth = ::fontWidth,
            highlight = highlight,
        ),
    )
}

private class TextAreaRenderer(
    private val textProvider: () -> String,
    private val caretProvider: () -> Int,
    private val selectionProvider: () -> IntRange?,
    private val font: Font,
    private val textColor: Color,
    private val placeholderColor: Color,
    private val caretColor: Color,
    private val selectionColor: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
    private val textScale: Float,
    private val lineHeightPx: Int,
    private val fontWidth: (String) -> Int,
    private val highlight: ((String) -> List<HlSpan>)? = null,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val text = textProvider()
        val caret = caretProvider()
        val w = node.layoutWidth
        val h = node.layoutHeight

        node.styleModifiers.filterIsInstance<BackgroundModifier>().lastOrNull()
            ?.let { fillRect(0, 0, w, h, it.color) }
        node.styleModifiers.filterIsInstance<BorderModifier>().lastOrNull()
            ?.let { drawBorder(0, 0, w, h, it.stroke.width, it.stroke.color) }

        if (text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
            drawScaledText(placeholder, 0, 0, placeholderColor)
            return
        }

        val lines = text.split('\n')

        // 1. selection highlight (under the text). [selLo, selHi) are absolute offsets.
        val sel = selectionProvider()
        if (sel != null) {
            val selLo = sel.first
            val selHi = sel.last + 1
            var off = 0
            var y = 0
            for (line in lines) {
                if (y + lineHeightPx > h) break
                val lineStart = off
                val lineEnd = off + line.length
                val lo = maxOf(selLo, lineStart)
                val hi = minOf(selHi, lineEnd + 1) // +1 includes the newline char
                if (lo < hi) {
                    val c0 = (lo - lineStart).coerceIn(0, line.length)
                    val c1 = (hi - lineStart).coerceIn(0, line.length)
                    val x0 = fontWidth(line.substring(0, c0))
                    var rectW = fontWidth(line.substring(0, c1)) - x0
                    if (hi > lineEnd) rectW += 4 // newline selected → trailing hint
                    if (rectW > 0 && x0 < w) fillRect(x0, y, rectW.coerceAtMost(w - x0), lineHeightPx, selectionColor)
                }
                off = lineEnd + 1
                y += lineHeightPx
            }
        }

        // 2. text
        var y = 0
        for (line in lines) {
            if (y + lineHeightPx > h) break
            if (line.isNotEmpty()) {
                val hl = highlight
                if (hl == null) drawScaledText(truncateToWidth(line, w), 0, y, textColor)
                else drawHighlightedLine(line, y, w, hl(line))
            }
            y += lineHeightPx
        }

        // 3. caret
        if (isFocused() && blinkOn()) {
            var lineIdx = 0; var lineStart = 0
            for (i in 0 until caret) if (text[i] == '\n') { lineIdx++; lineStart = i + 1 }
            val cy = lineIdx * lineHeightPx
            if (cy + lineHeightPx <= h) {
                val caretCol = caret - lineStart
                val lineText = run {
                    var end = lineStart
                    while (end < text.length && text[end] != '\n') end++
                    text.substring(lineStart, end)
                }
                val cx = fontWidth(lineText.substring(0, caretCol.coerceAtMost(lineText.length)))
                if (cx < w) fillRect(cx, cy, 1, lineHeightPx, caretColor)
            }
        }
    }

    private fun NwCanvas.drawHighlightedLine(line: String, y: Int, w: Int, spans: List<HlSpan>) {
        val len = line.length
        var cursor = 0
        fun emit(start: Int, end: Int, color: Color) {
            if (start >= end) return
            val x = fontWidth(line.substring(0, start))
            if (x >= w) return
            val visible = truncateToWidth(line.substring(start, end), w - x)
            if (visible.isNotEmpty()) drawScaledText(visible, x, y, color)
        }
        for (span in spans) {
            val s = span.start.coerceIn(0, len)
            val e = span.end.coerceIn(0, len)
            if (s > cursor) emit(cursor, s, textColor)
            emit(s, e, span.kind.color)
            cursor = maxOf(cursor, e)
        }
        if (cursor < len) emit(cursor, len, textColor)
    }

    private fun truncateToWidth(line: String, maxWidth: Int): String {
        if (fontWidth(line) <= maxWidth) return line
        var lo = 0; var hi = line.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fontWidth(line.substring(0, mid)) <= maxWidth) lo = mid else hi = mid - 1
        }
        return line.substring(0, lo)
    }

    private fun NwCanvas.drawScaledText(text: String, x: Int, y: Int, color: Color) {
        if (textScale == 1f) { drawText(text, x, y, color); return }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), (offsetY + y).toFloat(), 0f)
        gfx.pose().scale(textScale, textScale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, true)
        gfx.pose().popPose()
    }
}
