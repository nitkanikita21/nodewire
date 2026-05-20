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
        is ContextMenuTarget.Group -> buildGroupItems(editor, target, toast)
        is ContextMenuTarget.Comment -> listOf(
            ContextMenuItem.Action("🗑 Delete comment") {
                editor.removeComment(target.commentId)
                toast?.info("Comment deleted")
            }
        )
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
            label = "${categoryIcon(category)} ${category.displayName}",
            items = types.map { type ->
                ContextMenuItem.Action(label = type.displayName) {
                    editor.addNode(type.newInstance(target.world))
                    toast?.success("Added ${type.displayName}")
                }
            },
        )
    }
    // Templates live as one extra "category" at the tail of the Add Node
    // submenu list — same shape as node categories so the user navigates
    // them through the same flow ("➕ Add Node → 📦 Templates → ...").
    val files = dev.nitka.nodewire.client.screen.GroupFiles.list()
    val templatesSubmenu: ContextMenuItem = if (files.isEmpty()) {
        ContextMenuItem.Action("📦 Templates: (none saved)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "📦 Templates",
            items = files.map { f ->
                ContextMenuItem.Action(f) {
                    val id = editor.insertTemplate(f, target.world)
                    if (id != null) toast?.success("Inserted $f") else toast?.warning("Insert refused (cycle)")
                }
            },
        )
    }
    val addNodeItems = categorySubmenus + templatesSubmenu
    return listOf(
        ContextMenuItem.Submenu(label = "➕ Add Node", items = addNodeItems),
        ContextMenuItem.Separator,
        ContextMenuItem.Action("💬 Add Comment") {
            editor.addComment(target.world)
            toast?.info("Comment added")
        },
        ContextMenuItem.Separator,
        ContextMenuItem.Action(label = "📤 Export graph to file") {
            val path = GraphExporter.exportToFile(editor.graph, editor.pos)
            if (path != null) toast?.success("Exported to $path")
            else toast?.warning("Export failed — see log")
        },
        ContextMenuItem.Action(label = "📋 Copy graph SNBT") {
            if (GraphExporter.copyToClipboard(editor.graph)) toast?.success("Copied SNBT to clipboard")
            else toast?.warning("Copy failed — see log")
        },
    )
}

private fun categoryIcon(c: NodeCategory): String = when (c) {
    NodeCategory.IO -> "🔌"
    NodeCategory.LOGIC -> "🧮"
    NodeCategory.MATH -> "➗"
    NodeCategory.VECTOR -> "➡"
    NodeCategory.CONVERSION -> "🔄"
    NodeCategory.FLOW -> "🌊"
    NodeCategory.CONSTANTS -> "🔢"
}

private fun buildNodeItems(
    editor: EditorState,
    target: ContextMenuTarget.Node,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> = listOf(
    ContextMenuItem.Action(label = "✏ Rename") {
        editor.renamingNode = target.nodeId
    },
    ContextMenuItem.Separator,
    ContextMenuItem.Action(label = "📑 Duplicate") {
        editor.duplicateNode(target.nodeId)
        toast?.info("Duplicated")
    },
    ContextMenuItem.Separator,
    ContextMenuItem.Action(label = "🗑 Delete") {
        editor.removeNode(target.nodeId)
        toast?.info("Deleted")
    },
)

private fun buildGroupItems(
    editor: EditorState,
    target: ContextMenuTarget.Group,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> {
    val g = editor.graph.groups.firstOrNull { it.id == target.groupId } ?: return emptyList()
    val collapseLabel = if (g.collapsed) "🔼 Expand" else "🔽 Collapse"
    val items = mutableListOf<ContextMenuItem>(
        ContextMenuItem.Action("✏ Rename") { editor.renamingGroup = target.groupId },
        ContextMenuItem.Action(collapseLabel) { editor.toggleCollapsed(target.groupId) },
    )
    if (g.templateFile == null) {
        items.add(ContextMenuItem.Action("💾 Save as template…") {
            editor.pendingSaveTemplateForGroup = target.groupId
        })
    } else {
        items.add(ContextMenuItem.Action("🔗 Unlink (template: ${g.templateFile})") {
            editor.unlinkGroup(target.groupId); toast?.info("Unlinked")
        })
    }
    items.add(ContextMenuItem.Separator)
    items.add(ContextMenuItem.Action("✂ Ungroup") {
        editor.ungroup(target.groupId); toast?.info("Ungrouped")
    })
    return items
}
