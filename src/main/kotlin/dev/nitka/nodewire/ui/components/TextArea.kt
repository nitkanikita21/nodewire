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
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import org.lwjgl.glfw.GLFW

/**
 * Multi-line plain-text input.
 *
 * Designed for free-form comments rather than form fields, so it
 * intentionally diverges from [TextInput]:
 *   * `Enter` inserts a newline; `Ctrl+Enter` releases focus.
 *   * No selection or undo/redo — keep complexity small. Copy/paste of
 *     the full text via the surrounding context menu can be added later.
 *   * Caret only (no selection highlight). Click positions the caret on
 *     whichever (line, column) the user clicked.
 */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val focusController = LocalKeyFocus.current
    val font = Minecraft.getInstance().font

    // Local state: text + caret offset (byte index into the text). Externally
    // driven via [value]; internal edits mirror back via onValueChange.
    var text by remember { mutableStateOf(value) }
    var caret by remember { mutableStateOf(value.length) }
    val cb = rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (value != text) {
            text = value
            caret = caret.coerceAtMost(text.length)
        }
    }

    val textScale = NwTheme.typography.caption.scale
    val lineHeightPx = (font.lineHeight * textScale).toInt().coerceAtLeast(1)

    fun fontWidth(s: String): Int = (font.width(s) * textScale).toInt()

    /** Commit a new text + caret pair. Single source of edits. */
    fun apply(newText: String, newCaret: Int) {
        text = newText
        caret = newCaret.coerceIn(0, newText.length)
        cb.value(newText)
    }

    fun insertString(s: String) {
        apply(text.substring(0, caret) + s + text.substring(caret), caret + s.length)
    }

    fun backspace() {
        if (caret == 0) return
        apply(text.substring(0, caret - 1) + text.substring(caret), caret - 1)
    }

    fun deleteForward() {
        if (caret >= text.length) return
        apply(text.substring(0, caret) + text.substring(caret + 1), caret)
    }

    /** Caret as (line, col) given an offset. Lines are split on '\n'. */
    fun lineColOf(offset: Int): Pair<Int, Int> {
        var lineIdx = 0
        var lineStart = 0
        for (i in 0 until offset) {
            if (text[i] == '\n') { lineIdx++; lineStart = i + 1 }
        }
        return lineIdx to (offset - lineStart)
    }

    /** Offset of the start of [lineIdx]. */
    fun offsetOfLineStart(lineIdx: Int): Int {
        if (lineIdx <= 0) return 0
        var seen = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                seen++
                if (seen == lineIdx) return i + 1
            }
        }
        return text.length
    }

    fun lineLength(lineIdx: Int): Int {
        val start = offsetOfLineStart(lineIdx)
        var end = start
        while (end < text.length && text[end] != '\n') end++
        return end - start
    }

    fun moveLeft() { caret = (caret - 1).coerceAtLeast(0) }
    fun moveRight() { caret = (caret + 1).coerceAtMost(text.length) }
    fun moveUp() {
        val (line, col) = lineColOf(caret)
        if (line == 0) { caret = 0; return }
        val targetLen = lineLength(line - 1)
        caret = offsetOfLineStart(line - 1) + col.coerceAtMost(targetLen)
    }
    fun moveDown() {
        val (line, col) = lineColOf(caret)
        val total = text.count { it == '\n' }
        if (line >= total) { caret = text.length; return }
        val targetLen = lineLength(line + 1)
        caret = offsetOfLineStart(line + 1) + col.coerceAtMost(targetLen)
    }
    fun moveHome() {
        val (line, _) = lineColOf(caret)
        caret = offsetOfLineStart(line)
    }
    fun moveEnd() {
        val (line, _) = lineColOf(caret)
        caret = offsetOfLineStart(line) + lineLength(line)
    }

    val handler = remember {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean {
                if (!enabled) return false
                return when (event) {
                    is KeyEvent.Char -> {
                        val mods = event.modifiers
                        val ctrlAltSuper = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER
                        if (mods and ctrlAltSuper != 0) false
                        else { insertString(Character.toChars(event.codePoint).concatToString()); true }
                    }
                    is KeyEvent.Press -> when (event.keyCode) {
                        InputConstants.KEY_BACKSPACE -> { backspace(); true }
                        InputConstants.KEY_DELETE -> { deleteForward(); true }
                        InputConstants.KEY_LEFT -> { moveLeft(); true }
                        InputConstants.KEY_RIGHT -> { moveRight(); true }
                        InputConstants.KEY_UP -> { moveUp(); true }
                        InputConstants.KEY_DOWN -> { moveDown(); true }
                        InputConstants.KEY_HOME -> { moveHome(); true }
                        InputConstants.KEY_END -> { moveEnd(); true }
                        InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                            if (event.modifiers and GLFW.GLFW_MOD_CONTROL != 0) {
                                focusController?.release(this); true
                            } else { insertString("\n"); true }
                        }
                        InputConstants.KEY_ESCAPE -> { focusController?.release(this); true }
                        else -> false
                    }
                    else -> false
                }
            }
        }
    }
    val focused = focusController?.isFocused(handler) == true
    DisposableEffect(handler) { onDispose { focusController?.release(handler) } }

    // Caret-blink ticker.
    var blinkTick by remember { mutableStateOf(0) }
    LaunchedEffect(focused) { if (focused) while (true) { delay(50); blinkTick++ } }
    val blinkOn = ((Util.getMillis() % 1000L) < 530L)
    @Suppress("UNUSED_EXPRESSION") blinkTick

    val bg = when {
        focused -> NwTheme.colors.surfacePressed
        else -> NwTheme.colors.surfaceHover
    }

    Layout(
        modifier = modifier
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2)
            .onHover { /* hover state could feed bg later */ }
            .pointerInput { ev, localX, localY ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        // Compute caret from (x, y).
                        val lineIdx = (localY / lineHeightPx).coerceAtLeast(0)
                        val totalLines = text.count { it == '\n' } + 1
                        val clampedLine = lineIdx.coerceAtMost(totalLines - 1)
                        val lineStart = offsetOfLineStart(clampedLine)
                        val lineLen = lineLength(clampedLine)
                        val lineText = text.substring(lineStart, lineStart + lineLen)
                        // Binary search for click column.
                        var lo = 0; var hi = lineText.length
                        while (lo < hi) {
                            val mid = (lo + hi + 1) / 2
                            if (fontWidth(lineText.substring(0, mid)) <= localX) lo = mid else hi = mid - 1
                        }
                        caret = lineStart + lo
                        true
                    }
                    else -> false
                }
            },
        renderer = TextAreaRenderer(
            textProvider = { text },
            caretProvider = { caret },
            font = font,
            textColor = NwTheme.colors.onSurface,
            placeholderColor = NwTheme.colors.onSurfaceDisabled,
            caretColor = NwTheme.colors.accent,
            placeholder = placeholder,
            isFocused = { focused },
            blinkOn = { blinkOn },
            textScale = textScale,
            lineHeightPx = lineHeightPx,
            fontWidth = ::fontWidth,
        ),
    )
}

private class TextAreaRenderer(
    private val textProvider: () -> String,
    private val caretProvider: () -> Int,
    private val font: Font,
    private val textColor: Color,
    private val placeholderColor: Color,
    private val caretColor: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
    private val textScale: Float,
    private val lineHeightPx: Int,
    private val fontWidth: (String) -> Int,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val text = textProvider()
        val caret = caretProvider()
        val w = node.layoutWidth
        val h = node.layoutHeight

        // Paint background + border from style modifiers (overrides default surface render).
        node.styleModifiers.filterIsInstance<BackgroundModifier>().lastOrNull()
            ?.let { fillRect(0, 0, w, h, it.color) }
        node.styleModifiers.filterIsInstance<BorderModifier>().lastOrNull()
            ?.let { drawBorder(0, 0, w, h, it.stroke.width, it.stroke.color) }

        if (text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
            drawScaledText(placeholder, 0, 0, placeholderColor)
            return
        }

        // Render each line, clipped to the node's box. Lines whose top
        // edge already lies beyond the bottom of the node are skipped so
        // text doesn't overflow the comment card.
        val lines = text.split('\n')
        var y = 0
        for (line in lines) {
            if (y + lineHeightPx > h) break
            if (line.isNotEmpty()) {
                // Truncate per-line text to the visible width so it doesn't
                // bleed past the right edge either.
                val visible = truncateToWidth(line, w)
                drawScaledText(visible, 0, y, textColor)
            }
            y += lineHeightPx
        }

        if (isFocused() && blinkOn()) {
            // Compute caret position on its line.
            var lineIdx = 0
            var lineStart = 0
            for (i in 0 until caret) {
                if (text[i] == '\n') { lineIdx++; lineStart = i + 1 }
            }
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
        if (textScale == 1f) {
            drawText(text, x, y, color)
            return
        }
        gfx.pose().pushPose()
        gfx.pose().translate((offsetX + x).toFloat(), (offsetY + y).toFloat(), 0f)
        gfx.pose().scale(textScale, textScale, 1f)
        gfx.drawString(font, text, 0, 0, color.argb, true)
        gfx.pose().popPose()
    }
}
