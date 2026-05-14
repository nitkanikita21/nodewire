package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.ui.components.ContextMenu
import dev.nitka.nodewire.ui.components.ContextMenuItem
import dev.nitka.nodewire.ui.feedback.LocalToastManager
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
    // Capture toast manager once — it's a stable session-scoped object so
    // closures inside the menu items can fire toasts without re-reading
    // composition state on each click.
    val toast = LocalToastManager.current
    val items = when (target) {
        is ContextMenuTarget.Create -> buildCreateItems(editor, target, toast)
        is ContextMenuTarget.Node -> buildNodeItems(editor, target, toast)
    }
    ContextMenu(
        items = items,
        position = PopupPosition.AtScreen(target.screenX, target.screenY),
        onDismiss = { editor.closeContextMenu() },
    )
}

private fun buildCreateItems(
    editor: EditorState,
    target: ContextMenuTarget.Create,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> {
    val grouped = NodeTypeRegistry.byCategory()
    val categorySubmenus = NodeCategory.entries.mapNotNull { category ->
        val types = grouped[category] ?: return@mapNotNull null
        ContextMenuItem.Submenu(
            label = category.displayName,
            items = types.map { type ->
                ContextMenuItem.Action(label = type.displayName) {
                    editor.addNode(type.newInstance(target.world))
                    toast?.success("Added ${type.displayName}")
                }
            },
        )
    }
    return listOf(
        ContextMenuItem.Submenu(label = "Add Node", items = categorySubmenus),
    )
}

private fun buildNodeItems(
    editor: EditorState,
    target: ContextMenuTarget.Node,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> = listOf(
    ContextMenuItem.Action(label = "Duplicate") {
        editor.duplicateNode(target.nodeId)
        toast?.info("Duplicated")
    },
    ContextMenuItem.Separator,
    ContextMenuItem.Action(label = "Delete") {
        editor.removeNode(target.nodeId)
        toast?.info("Deleted")
    },
)
