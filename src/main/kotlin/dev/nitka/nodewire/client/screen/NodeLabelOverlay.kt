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
 * over the node card's header.
 */
@Composable
fun NodeLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val nodeId = editor.renamingNode ?: return
    val flow = editor.nodeFlow(nodeId) ?: return
    val node by flow.collectAsState()
    val x = node.pos.x.toInt() + 4
    val y = node.pos.y.toInt() + 2
    var text by remember(nodeId) { mutableStateOf(node.label ?: "") }
    Box(
        modifier = Modifier
            .absolutePosition(x, y)
            .width(CARD_WIDTH - 8),
    ) {
        TextInput(
            value = text,
            placeholder = "label",
            onValueChange = { text = it },
            onSubmit = {
                editor.setNodeLabel(nodeId, text)
                editor.renamingNode = null
            },
        )
    }
}
