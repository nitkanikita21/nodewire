package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Iterates over `editor.groups` and renders each as either a [GroupFrame]
 * (expanded) or a [GroupCollapsedTile] (collapsed). Member-node hiding
 * for the collapsed case is enforced by [NodeEditorScreen] via the
 * [hiddenNodesFor] helper.
 */
@Composable
fun GroupLayer() {
    val editor = LocalEditorState.current ?: return
    val groups by editor.groups.collectAsState()
    for (g in groups) {
        if (g.collapsed) GroupCollapsedTile(g) else GroupFrame(g)
    }
}

/** Set of node ids that the screen should NOT render as standalone cards. */
fun hiddenNodesFor(editor: EditorState): Set<dev.nitka.nodewire.graph.NodeId> {
    val groups = editor.graph.groups
    val byId = groups.associateBy { it.id }
    val hidden = HashSet<dev.nitka.nodewire.graph.NodeId>()
    for (g in groups) {
        if (!g.collapsed) continue
        hidden.addAll(GroupProxyPins.memberClosure(g, byId))
    }
    return hidden
}
