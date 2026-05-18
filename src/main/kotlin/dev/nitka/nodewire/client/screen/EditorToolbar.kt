package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.net.SetBlockNamePacket
import dev.nitka.nodewire.ui.components.ContextMenu
import dev.nitka.nodewire.ui.components.ContextMenuItem
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.feedback.LocalToastManager
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Thin top toolbar. Layout:
 *
 *   [graph name input] [File ▾] [Edit ▾] [View ▾] ──── [Bindings…]
 *
 * The three menu buttons open anchored [ContextMenu]s with grouped
 * actions — keeps the bar short while exposing the same operations the
 * keyboard shortcuts already provide.
 */
@Composable
fun EditorToolbar(pos: BlockPos, onOpenBindings: () -> Unit) {
    val editor = LocalEditorState.current ?: return
    val toast = LocalToastManager.current
    val name by editor.blockName.collectAsState()
    val scope = rememberCoroutineScope()
    val debouncer = remember { NameDebouncer(scope) }

    // Save-as dialog visibility — local to the toolbar; closing it doesn't
    // affect the rest of the editor state.
    var saveAsOpen by remember { mutableStateOf(false) }

    // Which top-level menu (if any) is open. Tracking anchors per slot
    // because we need the popup to land directly below the clicked label.
    var openMenu by remember { mutableStateOf<MenuId?>(null) }
    val anchors = remember { HashMap<MenuId, LayoutCoordinates>() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NwTheme.colors.surface)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
    ) {
        TextInput(
            modifier = Modifier.width(NAME_INPUT_WIDTH),
            value = name,
            placeholder = "Graph name",
            onValueChange = { next ->
                editor.setBlockName(next)
                debouncer.schedule(pos, next)
            },
        )

        MenuButton(
            label = "File",
            isOpen = openMenu == MenuId.File,
            onClick = { openMenu = if (openMenu == MenuId.File) null else MenuId.File },
            onPositioned = { anchors[MenuId.File] = it },
        )
        MenuButton(
            label = "Edit",
            isOpen = openMenu == MenuId.Edit,
            onClick = { openMenu = if (openMenu == MenuId.Edit) null else MenuId.Edit },
            onPositioned = { anchors[MenuId.Edit] = it },
        )
        MenuButton(
            label = "View",
            isOpen = openMenu == MenuId.View,
            onClick = { openMenu = if (openMenu == MenuId.View) null else MenuId.View },
            onPositioned = { anchors[MenuId.View] = it },
        )

        Box(modifier = Modifier.weight(1f))

        // TODO(post-port): TC status indicator removed — no 1.21.1 build
        MenuButton(
            label = "Bindings…",
            isOpen = false,
            onClick = onOpenBindings,
            onPositioned = { },
        )
    }

    val anchorCoords = openMenu?.let { anchors[it] }
    if (openMenu != null && anchorCoords != null) {
        val items = when (openMenu) {
            MenuId.File -> fileMenuItems(
                editor = editor,
                currentName = name,
                onSaveAs = { saveAsOpen = true },
                toast = toast,
            )
            MenuId.Edit -> editMenuItems(editor, toast)
            MenuId.View -> viewMenuItems(editor)
            null -> emptyList()
        }
        ContextMenu(
            items = items,
            position = PopupPosition.Anchored(
                anchor = anchorCoords,
                placement = PopupPlacement.Below,
                gap = 1,
            ),
            onDismiss = { openMenu = null },
        )
    }

    if (saveAsOpen) {
        SaveAsDialog(
            initial = name,
            onDismiss = { saveAsOpen = false },
            onConfirm = { chosen ->
                val safe = GraphFiles.sanitize(chosen)
                if (safe.isEmpty()) {
                    toast?.warning("Invalid name")
                    return@SaveAsDialog
                }
                val path = GraphFiles.save(safe, editor.snapshotGraph())
                if (path != null) {
                    editor.setBlockName(safe)
                    debouncer.schedule(pos, safe)
                    toast?.success("Saved as $safe")
                } else {
                    toast?.warning("Save failed — see log")
                }
            },
        )
    }
}

private enum class MenuId { File, Edit, View }

/**
 * Compact ghost-style toolbar button. Uses a tight padding preset so the
 * whole bar stays as thin as the caption typography permits. The hover
 * background reuses the menu's surfaceHover for visual consistency with
 * any open submenu rows.
 */
@Composable
private fun MenuButton(
    label: String,
    isOpen: Boolean,
    onClick: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        isOpen || pressed -> NwTheme.colors.surfacePressed
        hovered -> NwTheme.colors.surfaceHover
        else -> NwTheme.colors.surface
    }
    Surface(
        modifier = Modifier
            .onPositioned(onPositioned)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Press -> { pressed = true; true }
                    is PointerEvent.Release -> {
                        val was = pressed
                        pressed = false
                        if (was && hovered) onClick()
                        true
                    }
                    else -> false
                }
            },
        style = SurfaceStyle(
            color = bg,
            shape = NwTheme.shapes.small,
            border = null,
            padding = PaddingValues(
                horizontal = NwTheme.dimens.space6,
                vertical = NwTheme.dimens.space2,
            ),
        ),
    ) {
        Text(label, style = NwTheme.typography.caption)
    }
}

private fun fileMenuItems(
    editor: EditorState,
    currentName: String,
    onSaveAs: () -> Unit,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> {
    val saved = GraphFiles.list()
    val openSubmenu = if (saved.isEmpty()) {
        ContextMenuItem.Action("Open: (no saved graphs)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "Open",
            items = saved.map { fileName ->
                ContextMenuItem.Action(fileName) {
                    val loaded: NodeGraph? = GraphFiles.load(fileName)
                    if (loaded == null) {
                        toast?.warning("Load failed: $fileName")
                    } else {
                        editor.replaceGraph(loaded)
                        editor.setBlockName(fileName)
                        toast?.success("Loaded $fileName")
                    }
                }
            },
        )
    }
    val deleteSubmenu = if (saved.isEmpty()) {
        ContextMenuItem.Action("Delete: (no saved graphs)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "Delete saved",
            items = saved.map { fileName ->
                ContextMenuItem.Action(fileName) {
                    if (GraphFiles.delete(fileName)) toast?.info("Deleted $fileName")
                    else toast?.warning("Delete failed: $fileName")
                }
            },
        )
    }
    val groupFiles = GroupFiles.list()
    val insertGroupItem: ContextMenuItem = if (groupFiles.isEmpty()) {
        ContextMenuItem.Action("Insert group: (none saved)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "Insert group",
            items = groupFiles.map { f ->
                ContextMenuItem.Action(f) {
                    val canvas = editor.canvasState
                    val pos = if (canvas != null) dev.nitka.nodewire.graph.CanvasPos(canvas.cursorWorldX, canvas.cursorWorldY)
                    else dev.nitka.nodewire.graph.CanvasPos.Zero
                    val id = editor.insertTemplate(f, pos)
                    if (id != null) toast?.success("Inserted $f") else toast?.warning("Insert refused")
                }
            },
        )
    }
    val saveLabel = if (GraphFiles.sanitize(currentName).isEmpty())
        "Save (set name first)" else "Save '$currentName'"
    return listOf(
        ContextMenuItem.Action(saveLabel) {
            val safe = GraphFiles.sanitize(currentName)
            if (safe.isEmpty()) {
                toast?.warning("Set a graph name first")
                return@Action
            }
            if (GraphFiles.save(safe, editor.snapshotGraph()) != null)
                toast?.success("Saved $safe")
            else toast?.warning("Save failed — see log")
        },
        ContextMenuItem.Action("Save as…", onSaveAs),
        openSubmenu,
        insertGroupItem,
        deleteSubmenu,
        ContextMenuItem.Separator,
        ContextMenuItem.Action("Export SNBT to file") {
            val path = GraphExporter.exportToFile(editor.snapshotGraph(), editor.pos)
            if (path != null) toast?.success("Exported to $path")
            else toast?.warning("Export failed — see log")
        },
        ContextMenuItem.Action("Copy SNBT to clipboard") {
            if (GraphExporter.copyToClipboard(editor.snapshotGraph()))
                toast?.success("Copied SNBT")
            else toast?.warning("Copy failed — see log")
        },
    )
}

private fun editMenuItems(
    editor: EditorState,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> = listOf(
    ContextMenuItem.Action("Undo") { editor.undoGraph() },
    ContextMenuItem.Action("Redo") { editor.redoGraph() },
    ContextMenuItem.Separator,
    ContextMenuItem.Action("Cut") { editor.cutSelectedToClipboard() },
    ContextMenuItem.Action("Copy") { editor.copySelectedToClipboard() },
    ContextMenuItem.Action("Paste") {
        val c = editor.canvasState
        editor.pasteFromClipboard(c?.cursorWorldX ?: 0f, c?.cursorWorldY ?: 0f)
    },
    ContextMenuItem.Action("Duplicate") { editor.duplicateSelected() },
    ContextMenuItem.Action("Delete") { editor.deleteSelected() },
    ContextMenuItem.Separator,
    ContextMenuItem.Action("Select all") { editor.selectAll() },
)

private fun viewMenuItems(editor: EditorState): List<ContextMenuItem> = listOf(
    ContextMenuItem.Action("Frame all") { editor.frameAll() },
    ContextMenuItem.Action("Frame selection") { editor.frameSelectedOrAll() },
)

private class NameDebouncer(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var pending: Job? = null
    fun schedule(pos: BlockPos, name: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            PacketDistributor.sendToServer(SetBlockNamePacket(pos, name))
        }
    }
    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}

private const val NAME_INPUT_WIDTH = 180
