package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.width

/**
 * Inline rename popup for the node currently in `editor.renamingNode`.
 * Lives inside the NodeCanvas so it pans/zooms with the world — sits
 * over the node card's header. Transparent so the underlying label/title
 * stays visible while editing; autofocused so typing works immediately.
 */
@Composable
fun NodeLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val nodeId = editor.renamingNode ?: return
    val flow = editor.nodeFlow(nodeId) ?: return
    val node by flow.collectAsState()
    val x = node.pos.x.toInt() + 4
    val y = node.pos.y.toInt() + 2
    // Re-key on (nodeId, node.label) so a save → reopen for the same node
    // seeds the field with the just-saved value, not stale in-progress text.
    var text by remember(nodeId, node.label) { mutableStateOf(node.label ?: "") }
    Box(
        modifier = Modifier
            .absolutePosition(x, y)
            .width(CARD_WIDTH - 8),
    ) {
        TextInput(
            value = text,
            placeholder = "label",
            transparent = true,
            autoFocus = true,
            onValueChange = { text = it },
            onSubmit = {
                editor.setNodeLabel(nodeId, text)
                editor.renamingNode = null
            },
        )
    }
}
