package dev.nitka.nodewire.client.screen

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.ui.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Editor-level keyboard shortcuts. Dispatched from
 * [NodeEditorScreen.keyPressed] only when no TextInput holds focus.
 *
 * Mirror of [dev.nitka.nodewire.ui.input.text.TextFieldKeyBindings] in
 * style — data-driven so the table is greppable and the dispatcher is
 * trivial.
 *
 * `action` takes an [EditorState] and a `cursorWorldX/Y` pair so
 * paste-at-cursor knows where to land; non-paste actions ignore it.
 */
data class EditorKeyBinding(
    val keyCode: Int,
    val modifiers: Int = 0,
    val action: (editor: EditorState, cursorWorldX: Float, cursorWorldY: Float) -> Boolean,
)

object EditorKeyBindings {
    private const val MOD_MASK = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT or
        GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER

    val DEFAULT: List<EditorKeyBinding> = listOf(
        EditorKeyBinding(InputConstants.KEY_DELETE)                                  { e, _, _ -> e.deleteSelected(); true },
        EditorKeyBinding(InputConstants.KEY_BACKSPACE)                               { e, _, _ -> e.deleteSelected(); true },
        EditorKeyBinding(InputConstants.KEY_A, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.selectAll(); true },
        EditorKeyBinding(InputConstants.KEY_D, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.duplicateSelected(); true },
        EditorKeyBinding(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.copySelectedToClipboard(); true },
        EditorKeyBinding(InputConstants.KEY_X, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.cutSelectedToClipboard(); true },
        EditorKeyBinding(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL)                { e, cx, cy -> e.pasteFromClipboard(cx, cy); true },
        EditorKeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.undoGraph(); true },
        EditorKeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { e, _, _ -> e.redoGraph(); true },
        EditorKeyBinding(InputConstants.KEY_Y, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.redoGraph(); true },
        EditorKeyBinding(InputConstants.KEY_F)                                       { e, _, _ -> e.frameSelectedOrAll(); true },
        EditorKeyBinding(InputConstants.KEY_F, GLFW.GLFW_MOD_SHIFT)                  { e, _, _ -> e.frameAll(); true },
        EditorKeyBinding(InputConstants.KEY_G, GLFW.GLFW_MOD_CONTROL) { e, _, _ ->
            val id = e.createGroupFromSelection("Group")
            id != null
        },
        EditorKeyBinding(InputConstants.KEY_G, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { e, _, _ ->
            val ids = e.selectedNodes
            if (ids.isEmpty()) return@EditorKeyBinding false
            val toRemove = e.graph.groups.filter { g ->
                g.members.any { m -> m is dev.nitka.nodewire.graph.MemberRef.Node && m.id in ids }
            }.map { it.id }
            for (gid in toRemove) e.ungroup(gid)
            toRemove.isNotEmpty()
        },
        EditorKeyBinding(InputConstants.KEY_ESCAPE) { e, _, _ ->
            when {
                e.renamingNode != null -> { e.renamingNode = null; true }
                e.renamingGroup != null -> { e.renamingGroup = null; true }
                e.renamingEdge != null -> { e.renamingEdge = null; true }
                e.contextMenu != null -> { e.closeContextMenu(); true }
                e.selectedNodes.isNotEmpty() -> { e.clearSelection(); true }
                else -> false
            }
        },
    )

    fun match(bindings: List<EditorKeyBinding>, event: KeyEvent.Press): EditorKeyBinding? {
        val mods = event.modifiers and MOD_MASK
        return bindings.firstOrNull { it.keyCode == event.keyCode && it.modifiers == mods }
    }
}
