package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.ui.components.ContextMenu
import dev.nitka.nodewire.ui.components.ContextMenuItem
import dev.nitka.nodewire.ui.overlay.PopupPosition

/**
 * Renders the editor's right-click menu based on [target]:
 *   * [ContextMenuTarget.Create] → top-level "Add Node" entry with one
 *     submenu per [NodeCategory], leaves spawn the chosen type at the
 *     world position where the user right-clicked.
 *   * [ContextMenuTarget.Node] → per-node actions (Duplicate, Delete).
 */
@Composable
fun NodeContextMenu(target: ContextMenuTarget, editor: EditorState) {
    val items = when (target) {
        is ContextMenuTarget.Create -> buildCreateItems(editor, target)
        is ContextMenuTarget.Node -> buildNodeItems(editor, target)
    }
    ContextMenu(
        items = items,
        position = PopupPosition.AtScreen(target.screenX, target.screenY),
        onDismiss = { editor.closeContextMenu() },
    )
}

private fun buildCreateItems(editor: EditorState, target: ContextMenuTarget.Create): List<ContextMenuItem> {
    val grouped = NodeTypeRegistry.byCategory()
    val categorySubmenus = NodeCategory.entries.mapNotNull { category ->
        val types = grouped[category] ?: return@mapNotNull null
        ContextMenuItem.Submenu(
            label = category.displayName,
            items = types.map { type ->
                ContextMenuItem.Action(label = type.displayName) {
                    editor.addNode(type.newInstance(target.world))
                }
            },
        )
    }
    // Top-level wrapper keeps the menu's title focused — Blender does the
    // same ("Add" → categories → types) and it scales when we add other
    // canvas-level actions like Paste / Select All.
    return listOf(
        ContextMenuItem.Submenu(label = "Add Node", items = categorySubmenus),
    )
}

private fun buildNodeItems(editor: EditorState, target: ContextMenuTarget.Node): List<ContextMenuItem> = listOf(
    ContextMenuItem.Action(label = "Duplicate") {
        editor.duplicateNode(target.nodeId)
    },
    ContextMenuItem.Separator,
    ContextMenuItem.Action(label = "Delete") {
        editor.removeNode(target.nodeId)
    },
)
