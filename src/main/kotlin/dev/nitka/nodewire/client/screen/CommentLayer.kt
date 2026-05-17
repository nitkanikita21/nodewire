package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Renders every [dev.nitka.nodewire.graph.Comment] in the current graph.
 * Mounted under WireLayer so wires draw on top — comments are background
 * annotations.
 */
@Composable
fun CommentLayer() {
    val editor = LocalEditorState.current ?: return
    val comments by editor.comments.collectAsState()
    for (c in comments) CommentCard(c)
}
