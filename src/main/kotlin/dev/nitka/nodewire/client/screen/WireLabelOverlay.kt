package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.client.Minecraft

/**
 * Inline rename popup for the wire currently in `editor.renamingEdge`.
 * Lives inside the [NodeCanvas] so it pans/zooms with the world — sits
 * at the wire's midpoint in world coordinates. Transparent so the wire
 * remains visible under the field, autofocused so typing works straight
 * away, and width-tracks the entered text so it never clips.
 *
 * The label is written into the graph on every keystroke (not only on
 * Enter) — that way switching to another wire by clicking it doesn't
 * silently discard what was just typed.
 */
@Composable
fun WireLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val edge = editor.renamingEdge ?: return
    val positions = editor.pinPositions
    val from = positions.get(PinKey(edge.from.node, edge.from.pin, PinSide.Output)) ?: return
    val to = positions.get(PinKey(edge.to.node, edge.to.pin, PinSide.Input)) ?: return
    // Re-key on (from, to) — these uniquely identify an edge, and the
    // label is what we're editing right now, so including it would reset
    // the field on every keystroke once we autosave.
    var text by remember(edge.from, edge.to) {
        mutableStateOf(edge.label ?: "")
    }
    val font = Minecraft.getInstance().font
    val scale = NwTheme.typography.caption.scale
    val placeholder = "label"
    // Width grows with text — never below the placeholder's width so an
    // empty field is still visibly clickable. Padding accounts for caret
    // + a comfortable text margin on the right edge.
    val measured = (font.width(text.ifEmpty { placeholder }) * scale).toInt()
    val w = (measured + 8).coerceAtLeast(40)
    val midX = ((from.first + to.first) * 0.5f).toInt() - w / 2
    val midY = ((from.second + to.second) * 0.5f).toInt() - 6
    Box(
        modifier = Modifier
            .absolutePosition(midX, midY)
            .width(w),
    ) {
        TextInput(
            value = text,
            placeholder = placeholder,
            transparent = true,
            autoFocus = true,
            onValueChange = { text = it },
            onSubmit = {
                editor.setEdgeLabel(edge, text)
                editor.renamingEdge = null
            },
        )
    }
}
