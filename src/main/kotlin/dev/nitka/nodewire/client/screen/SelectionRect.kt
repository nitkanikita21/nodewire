package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlin.math.abs
import kotlin.math.min

/**
 * Rubber-band selection rectangle drawn inside the canvas pose so it pans
 * and zooms with the world. Renders only while [EditorState.selectionDragStart]
 * is non-null. Visual: translucent accent fill so a busy node-grid stays
 * readable underneath.
 */
@Composable
fun SelectionRect() {
    val editor = LocalEditorState.current ?: return
    val start = editor.selectionDragStart ?: return
    val current = editor.selectionDragCurrent ?: return
    val left = min(start.x, current.x).toInt()
    val top = min(start.y, current.y).toInt()
    val width = abs(current.x - start.x).toInt().coerceAtLeast(1)
    val height = abs(current.y - start.y).toInt().coerceAtLeast(1)

    // Translucent fill of the accent colour (~25% alpha) — uses the
    // theme's accent rather than a literal hex so it tracks light/dark
    // theme swaps if those ever land.
    val accent = NwTheme.colors.accent
    val fill = Color((accent.argb and 0x00FFFFFF) or 0x40000000.toInt())
    Box(
        modifier = Modifier
            .absolutePosition(left, top)
            .size(width, height)
            .background(fill, NwTheme.shapes.small),
    )
}
