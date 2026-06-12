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

/** Gap between the line-number column and the code, px. */
private const val GUTTER_GAP = 6

/** Wheel scroll speed: lines per notch. */
private const val WHEEL_LINES = 3

/** Undo history depth + typing-coalesce window. */
private const val UNDO_LIMIT = 200
private const val TYPE_COALESCE_MS = 600L

/** Caret-follow margin (px) kept visible to the right of the caret. */
private const val CARET_X_MARGIN = 16

/**
 * Multi-line code editor:
 *   * **Selection** — Shift+arrows/Home/End, Ctrl+A, mouse drag (Shift+click
 *     extends, double-click selects a word).
 *   * **Clipboard** — Ctrl+C / Ctrl+X / Ctrl+V (via Minecraft's clipboard).
 *   * **Undo / redo** — Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z); rapid typing
 *     coalesces into one undo step.
 *   * **Tab** — inserts [INDENT]; with a multi-line selection, Tab indents and
 *     Shift+Tab dedents the whole block. `Enter` auto-indents to match the
 *     current line.
 *   * **Scrolling** — mouse wheel (Shift+wheel = horizontal), PageUp/PageDown,
 *     and automatic caret-follow on every move/edit.
 *   * **Line numbers** — optional gutter ([lineNumbers]); the current line's
 *     number is emphasized.
 *   * **Word navigation** — Ctrl+Left/Right and Ctrl+Backspace/Delete.
 *   * `Ctrl+Enter` / `Esc` release focus.
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
    lineNumbers: Boolean = false,
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

    // Scroll offsets in px. Clamped on every edit/scroll; the renderer also
    // soft-clamps for drawing so a shrinking document never leaves a gap.
    var scrollX by remember { mutableStateOf(0) }
    var scrollY by remember { mutableStateOf(0) }

    val textScale = NwTheme.typography.caption.scale
    val lineHeightPx = (font.lineHeight * textScale).toInt().coerceAtLeast(1)
    fun fontWidth(s: String): Int = (font.width(s) * textScale).toInt()

    // Box padding — used by the modifier chain below AND the click/scroll math.
    val padH = NwTheme.dimens.space4
    val padV = NwTheme.dimens.space2

    // Content-area viewport (box minus padding minus gutter), written by the
    // renderer each frame; (0,0) until first paint.
    val viewport = remember { intArrayOf(0, 0) }

    fun lineCount(): Int = text.count { it == '\n' } + 1
    fun gutterWidth(): Int =
        if (!lineNumbers) 0 else fontWidth(lineCount().toString()) + GUTTER_GAP * 2

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
    fun lineTextOf(lineIdx: Int): String {
        val start = offsetOfLineStart(lineIdx)
        return text.substring(start, start + lineLength(lineIdx))
    }

    // ── scroll helpers ──────────────────────────────────────────────────
    fun maxScrollY(): Int = (lineCount() * lineHeightPx - viewport[1]).coerceAtLeast(0)
    fun maxScrollX(): Int {
        var widest = 0
        var start = 0
        while (start <= text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            val w = fontWidth(text.substring(start, end))
            if (w > widest) widest = w
            start = end + 1
        }
        return (widest + CARET_X_MARGIN - viewport[0]).coerceAtLeast(0)
    }
    fun clampScroll() {
        if (viewport[1] > 0) scrollY = scrollY.coerceIn(0, maxScrollY())
        if (viewport[0] > 0) scrollX = scrollX.coerceIn(0, maxScrollX())
    }

    /** Keep the caret inside the viewport after moves/edits. */
    fun ensureCaretVisible() {
        if (viewport[0] <= 0 || viewport[1] <= 0) return
        val (line, col) = lineColOf(caret)
        val cy = line * lineHeightPx
        if (cy < scrollY) scrollY = cy
        else if (cy + lineHeightPx > scrollY + viewport[1]) scrollY = cy + lineHeightPx - viewport[1]
        val cx = fontWidth(lineTextOf(line).substring(0, col))
        if (cx < scrollX) scrollX = (cx - CARET_X_MARGIN).coerceAtLeast(0)
        else if (cx > scrollX + viewport[0] - CARET_X_MARGIN) scrollX = cx - viewport[0] + CARET_X_MARGIN
        clampScroll()
    }

    // ── selection helpers ───────────────────────────────────────────────
    fun hasSelection(): Boolean = anchor.let { it != null && it != caret }
    fun selMin(): Int = minOf(anchor ?: caret, caret)
    fun selMax(): Int = maxOf(anchor ?: caret, caret)
    fun selectedText(): String = if (hasSelection()) text.substring(selMin(), selMax()) else ""

    // ── undo / redo ─────────────────────────────────────────────────────
    class Snap(val text: String, val caret: Int, val anchor: Int?)
    val undoStack = remember { ArrayDeque<Snap>() }
    val redoStack = remember { ArrayDeque<Snap>() }
    val snapMeta = remember { longArrayOf(0L, 0L) } // [lastSnapMs, lastWasTyping(0/1)]

    /** Record state BEFORE a mutation. Rapid typing coalesces into one step. */
    fun snapshot(typing: Boolean = false) {
        val now = Util.getMillis()
        if (typing && snapMeta[1] == 1L && now - snapMeta[0] < TYPE_COALESCE_MS) {
            snapMeta[0] = now
            return
        }
        undoStack.addLast(Snap(text, caret, anchor))
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        snapMeta[0] = now
        snapMeta[1] = if (typing) 1L else 0L
    }

    fun restore(s: Snap) {
        text = s.text
        caret = s.caret.coerceIn(0, s.text.length)
        anchor = s.anchor?.coerceIn(0, s.text.length)
        cb.value(s.text)
        snapMeta[1] = 0L
        ensureCaretVisible()
    }
    fun undoOp() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snap(text, caret, anchor))
        restore(s)
    }
    fun redoOp() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snap(text, caret, anchor))
        restore(s)
    }

    /** Commit text + caret; clears selection. Single source of simple edits. */
    fun apply(newText: String, newCaret: Int) {
        text = newText
        caret = newCaret.coerceIn(0, newText.length)
        anchor = null
        cb.value(newText)
        ensureCaretVisible()
    }

    fun deleteSelectionIfAny(): Boolean {
        if (!hasSelection()) { anchor = null; return false }
        val lo = selMin(); val hi = selMax()
        apply(text.substring(0, lo) + text.substring(hi), lo)
        return true
    }

    fun insertString(s: String, typing: Boolean = false) {
        snapshot(typing = typing && !hasSelection() && s.length == 1)
        val lo = if (hasSelection()) selMin() else caret
        val hi = if (hasSelection()) selMax() else caret
        apply(text.substring(0, lo) + s + text.substring(hi), lo + s.length)
    }

    fun backspace() {
        snapshot()
        if (deleteSelectionIfAny()) return
        if (caret == 0) return
        apply(text.substring(0, caret - 1) + text.substring(caret), caret - 1)
    }
    fun deleteForward() {
        snapshot()
        if (deleteSelectionIfAny()) return
        if (caret >= text.length) return
        apply(text.substring(0, caret) + text.substring(caret + 1), caret)
    }

    // ── caret movement (extend = hold shift to grow the selection) ───────
    fun place(newCaret: Int, extend: Boolean) {
        if (extend) { if (anchor == null) anchor = caret } else anchor = null
        caret = newCaret.coerceIn(0, text.length)
        ensureCaretVisible()
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
    fun movePagePos(down: Boolean): Int {
        val jump = (viewport[1] / lineHeightPx).coerceAtLeast(1)
        val (line, col) = lineColOf(caret)
        val target = (if (down) line + jump else line - jump)
            .coerceIn(0, text.count { it == '\n' })
        return offsetOfLineStart(target) + col.coerceAtMost(lineLength(target))
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
    fun cut() {
        if (!hasSelection()) return
        snapshot()
        keyboard.clipboard = selectedText()
        deleteSelectionIfAny()
    }
    fun paste() {
        val c = keyboard.clipboard ?: return
        if (c.isNotEmpty()) insertString(c.replace("\r\n", "\n").replace('\r', '\n'))
    }
    fun selectAll() { anchor = 0; caret = text.length }

    /** Enter that copies the current line's leading whitespace. */
    fun newlineAutoIndent() {
        val refLine = lineTextOf(lineOf(if (hasSelection()) selMin() else caret))
        val indent = refLine.takeWhile { it == ' ' }
        insertString("\n" + indent)
    }

    // ── block indent / dedent ────────────────────────────────────────────
    fun reindent(dedent: Boolean) {
        snapshot()
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
        ensureCaretVisible()
    }

    // ── caret-from-(x,y): box-local px → text offset ─────────────────────
    // Compensates padding, gutter and both scroll axes.
    fun offsetAt(boxX: Int, boxY: Int): Int {
        val localX = (boxX - padH - gutterWidth() + scrollX).coerceAtLeast(0)
        val localY = (boxY - padV + scrollY).coerceAtLeast(0)
        val lineIdx = (localY / lineHeightPx).coerceAtLeast(0)
        val clampedLine = lineIdx.coerceAtMost(lineCount() - 1)
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
                        else { insertString(Character.toChars(event.codePoint).concatToString(), typing = true); true }
                    }
                    is KeyEvent.Press -> {
                        val ctrl = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0
                        val shift = event.modifiers and GLFW.GLFW_MOD_SHIFT != 0
                        when (event.keyCode) {
                            // clipboard / select-all / undo-redo
                            GLFW.GLFW_KEY_A -> if (ctrl) { selectAll(); true } else false
                            GLFW.GLFW_KEY_C -> if (ctrl) { copy(); true } else false
                            GLFW.GLFW_KEY_X -> if (ctrl) { cut(); true } else false
                            GLFW.GLFW_KEY_V -> if (ctrl) { paste(); true } else false
                            GLFW.GLFW_KEY_Z -> if (ctrl) { if (shift) redoOp() else undoOp(); true } else false
                            GLFW.GLFW_KEY_Y -> if (ctrl) { redoOp(); true } else false
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
                                    snapshot()
                                    val w = wordLeftOf(caret); apply(text.substring(0, w) + text.substring(caret), w)
                                } else backspace()
                                true
                            }
                            InputConstants.KEY_DELETE -> {
                                if (ctrl && !hasSelection() && caret < text.length) {
                                    snapshot()
                                    val w = wordRightOf(caret); apply(text.substring(0, caret) + text.substring(w), caret)
                                } else deleteForward()
                                true
                            }
                            // movement (shift extends selection)
                            InputConstants.KEY_LEFT -> { place(if (ctrl) wordLeftOf(caret) else caret - 1, shift); true }
                            InputConstants.KEY_RIGHT -> { place(if (ctrl) wordRightOf(caret) else caret + 1, shift); true }
                            InputConstants.KEY_UP -> { place(if (ctrl) 0 else moveUpPos(), shift); true }
                            InputConstants.KEY_DOWN -> { place(if (ctrl) text.length else moveDownPos(), shift); true }
                            InputConstants.KEY_PAGEUP -> { place(movePagePos(down = false), shift); true }
                            InputConstants.KEY_PAGEDOWN -> { place(movePagePos(down = true), shift); true }
                            InputConstants.KEY_HOME -> { place(if (ctrl) 0 else homePos(), shift); true }
                            InputConstants.KEY_END -> { place(if (ctrl) text.length else endPos(), shift); true }
                            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                                if (ctrl) focusController?.release(this) else newlineAutoIndent(); true
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

    // Double-click word selection bookkeeping: [lastPressMs, lastPressOffset].
    val lastPress = remember { longArrayOf(0L, -1L) }

    Layout(
        modifier = modifier
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = padH, vertical = padV)
            .onHover { }
            .pointerInput { ev, localX, localY ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        val off = offsetAt(localX, localY)
                        val now = Util.getMillis()
                        val doubleClick = now - lastPress[0] < 400 && lastPress[1] == off.toLong()
                        lastPress[0] = now; lastPress[1] = off.toLong()
                        if (doubleClick) {
                            anchor = wordLeftOf(off.coerceAtMost(text.length))
                            caret = wordRightOf(off)
                        } else if (Screen.hasShiftDown()) {
                            place(off, extend = true)
                        } else {
                            caret = off; anchor = caret // empty sel; drag will grow it
                        }
                        true
                    }
                    is PointerEvent.Drag -> { caret = offsetAt(localX, localY); true } // anchor stays → selects
                    is PointerEvent.Scroll -> {
                        val step = (ev.deltaY * lineHeightPx * WHEEL_LINES).toInt()
                        if (Screen.hasShiftDown()) {
                            val m = maxScrollX()
                            if (m <= 0) return@pointerInput false
                            scrollX = (scrollX - step).coerceIn(0, m)
                        } else {
                            val m = maxScrollY()
                            if (m <= 0) return@pointerInput false
                            scrollY = (scrollY - step).coerceIn(0, m)
                        }
                        true
                    }
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
            gutterColor = NwTheme.colors.onSurfaceDisabled,
            gutterActiveColor = NwTheme.colors.onSurfaceMuted,
            gutterRule = NwTheme.colors.border,
            placeholder = placeholder,
            isFocused = { focused },
            blinkOn = { blinkOn },
            textScale = textScale,
            lineHeightPx = lineHeightPx,
            fontWidth = ::fontWidth,
            highlight = highlight,
            lineNumbers = lineNumbers,
            padH = padH,
            padV = padV,
            scrollXProvider = { scrollX },
            scrollYProvider = { scrollY },
            gutterWidthProvider = ::gutterWidth,
            viewportOut = viewport,
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
    private val gutterColor: Color,
    private val gutterActiveColor: Color,
    private val gutterRule: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
    private val textScale: Float,
    private val lineHeightPx: Int,
    private val fontWidth: (String) -> Int,
    private val highlight: ((String) -> List<HlSpan>)? = null,
    private val lineNumbers: Boolean = false,
    private val padH: Int = 0,
    private val padV: Int = 0,
    private val scrollXProvider: () -> Int = { 0 },
    private val scrollYProvider: () -> Int = { 0 },
    private val gutterWidthProvider: () -> Int = { 0 },
    private val viewportOut: IntArray = intArrayOf(0, 0),
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

        val gutterW = gutterWidthProvider()
        // Publish the content viewport for the composable's scroll math.
        val viewW = (w - padH * 2 - gutterW).coerceAtLeast(0)
        val viewH = (h - padV * 2).coerceAtLeast(0)
        viewportOut[0] = viewW
        viewportOut[1] = viewH

        if (text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
            drawScaledText(placeholder, padH + gutterW, padV, placeholderColor)
            return
        }

        val lines = text.split('\n')
        // Soft-clamp for drawing (the state clamps on edits; a shrunken
        // document mid-frame must not leave the view past the end).
        val maxSy = (lines.size * lineHeightPx - viewH).coerceAtLeast(0)
        val sy = scrollYProvider().coerceIn(0, maxSy)
        val sx = scrollXProvider().coerceAtLeast(0)

        val originX = padH + gutterW - sx
        val originY = padV - sy
        val firstLine = (sy / lineHeightPx).coerceAtLeast(0)
        val lastLine = ((sy + viewH) / lineHeightPx + 1).coerceAtMost(lines.size - 1)
        val caretLine = run {
            var l = 0
            for (i in 0 until caret.coerceAtMost(text.length)) if (text[i] == '\n') l++
            l
        }

        // ── line numbers (clipped to the gutter; scrolls with Y only) ──
        if (lineNumbers && gutterW > 0) {
            pushClip(0, padV, padH + gutterW, viewH)
            for (i in firstLine..lastLine) {
                val label = (i + 1).toString()
                val ly = padV + i * lineHeightPx - sy
                val lx = padH + gutterW - GUTTER_GAP - fontWidth(label)
                drawScaledText(label, lx, ly, if (i == caretLine) gutterActiveColor else gutterColor)
            }
            popClip()
            fillRect(padH + gutterW - GUTTER_GAP / 2, padV, 1, viewH, gutterRule)
        }

        // ── text area (selection + code + caret), clipped ──
        pushClip(padH + gutterW, padV, viewW, viewH)

        // 1. selection highlight (under the text). [selLo, selHi) are absolute offsets.
        val sel = selectionProvider()
        if (sel != null) {
            val selLo = sel.first
            val selHi = sel.last + 1
            var off = 0
            for ((i, line) in lines.withIndex()) {
                if (i > lastLine) break
                val lineStart = off
                val lineEnd = off + line.length
                off = lineEnd + 1
                if (i < firstLine) continue
                val lo = maxOf(selLo, lineStart)
                val hi = minOf(selHi, lineEnd + 1) // +1 includes the newline char
                if (lo < hi) {
                    val c0 = (lo - lineStart).coerceIn(0, line.length)
                    val c1 = (hi - lineStart).coerceIn(0, line.length)
                    val x0 = fontWidth(line.substring(0, c0))
                    var rectW = fontWidth(line.substring(0, c1)) - x0
                    if (hi > lineEnd) rectW += 4 // newline selected → trailing hint
                    if (rectW > 0) fillRect(originX + x0, padV + i * lineHeightPx - sy, rectW, lineHeightPx, selectionColor)
                }
            }
        }

        // 2. text — only the visible line range.
        for (i in firstLine..lastLine) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val y = padV + i * lineHeightPx - sy
            val hl = highlight
            if (hl == null) drawScaledText(line, originX, y, textColor)
            else drawHighlightedLine(line, originX, y, hl(line))
        }

        // 3. caret
        if (isFocused() && blinkOn()) {
            var lineStart = 0
            for (i in 0 until caret) if (text[i] == '\n') lineStart = i + 1
            val caretCol = caret - lineStart
            val lineText = run {
                var end = lineStart
                while (end < text.length && text[end] != '\n') end++
                text.substring(lineStart, end)
            }
            val cx = originX + fontWidth(lineText.substring(0, caretCol.coerceAtMost(lineText.length)))
            val cy = padV + caretLine * lineHeightPx - sy
            fillRect(cx, cy, 1, lineHeightPx, caretColor)
        }

        popClip()
    }

    private fun NwCanvas.drawHighlightedLine(line: String, originX: Int, y: Int, spans: List<HlSpan>) {
        val len = line.length
        var cursor = 0
        fun emit(start: Int, end: Int, color: Color) {
            if (start >= end) return
            val x = originX + fontWidth(line.substring(0, start))
            drawScaledText(line.substring(start, end), x, y, color)
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

    private fun NwCanvas.drawScaledText(text: String, x: Int, y: Int, color: Color) {
        if (textScale == 1f) { drawText(text, x, y, color); return }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), (offsetY + y).toFloat(), 0f)
        gfx.pose().scale(textScale, textScale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, true)
        gfx.pose().popPose()
    }
}
