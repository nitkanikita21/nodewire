package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key

/**
 * Expanded-group frames only — drawn UNDER wires so the semi-transparent
 * frame backdrop doesn't cover dots/curves. Mount before [WireLayer].
 */
@Composable
fun GroupFramesLayer() {
    val editor = LocalEditorState.current ?: return
    val groups by editor.groups.collectAsState()
    for (g in groups) if (!g.collapsed) key(g.id) { GroupFrame(g) }
}

/**
 * Collapsed-group tiles only — drawn ABOVE wires, like nodes, so wire
 * endpoints visually terminate against the tile's proxy pin handles.
 * Mount after [WireLayer] and alongside [NodeCard]s.
 */
@Composable
fun GroupCollapsedLayer() {
    val editor = LocalEditorState.current ?: return
    val groups by editor.groups.collectAsState()
    for (g in groups) if (g.collapsed) key(g.id) { GroupCollapsedTile(g) }
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
