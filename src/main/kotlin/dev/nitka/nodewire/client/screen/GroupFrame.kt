package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupBbox
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Expanded-state visual for a [Group] — a semi-transparent frame around
 * the bbox of its members, with a header bar that drags every member.
 *
 * Bbox is recomputed every recomposition from current member node
 * positions and their measured card sizes (via `EditorState.cardSize`).
 * Header drag delegates to `EditorState.moveGroup` so the move applies
 * uniformly to all (recursively included) members.
 */
@Composable
fun GroupFrame(group: Group) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    val nodeIds by editor.nodes.collectAsState()
    val allGroups by editor.groups.collectAsState()
    val allGroupsById = remember(allGroups) { allGroups.associateBy { it.id } }
    val closure: Set<dev.nitka.nodewire.graph.NodeId> = remember(group, nodeIds, allGroupsById) {
        GroupProxyPins.memberClosure(group, allGroupsById)
    }
    val rects = closure.mapNotNull { id ->
        val n = editor.nodeFlow(id)?.collectAsState()?.value ?: return@mapNotNull null
        val sz = editor.cardSize(id) ?: (200 to 60)
        n.pos to sz
    }
    val bbox = GroupBbox.compute(group.pos, rects)
    val pad = NwTheme.dimens.space8
    val w = (bbox.maxX - bbox.minX).toInt() + pad * 2
    val h = (bbox.maxY - bbox.minY).toInt() + pad * 2 + GROUP_HEADER_HEIGHT
    var lastHeaderPressMillis by remember { mutableStateOf(0L) }

    val selected = editor.isGroupSelected(group.id)
    val borderColor = if (selected) NwTheme.colors.accent else NwTheme.colors.border
    val borderWidth = if (selected) 2 else 1
    Box(
        modifier = Modifier
            .absolutePosition((bbox.minX - pad).toInt(), (bbox.minY - pad - GROUP_HEADER_HEIGHT).toInt())
            .size(w, h)
            // Semi-transparent so member nodes underneath stay legible.
            // Header pill (drawn next, opaque) provides clear visual
            // separation between drag-handle area and member region.
            .background(NwTheme.colors.surfaceHover.copy(alpha = 0.90f))
            .border(BorderStroke(borderWidth, borderColor), NwTheme.shapes.medium),
    ) {
        Surface(
            modifier = Modifier
                .size(w, GROUP_HEADER_HEIGHT)
                .pointerInput { ev, x, y ->
                    when (ev) {
                        is PointerEvent.Drag -> {
                            val zoom = canvas?.zoom ?: 1f
                            val dxW = ev.deltaX / zoom
                            val dyW = ev.deltaY / zoom
                            // Same rule as NodeCard/CommentCard: if this item
                            // is in the selection, the drag moves the whole
                            // selection uniformly; otherwise fall back to a
                            // single-group move.
                            if (editor.isGroupSelected(group.id)) {
                                editor.moveSelected(dxW, dyW)
                            } else {
                                editor.moveGroup(group.id, dxW, dyW)
                            }
                            true
                        }
                        is PointerEvent.Press -> {
                            when (ev.button) {
                                LEFT_BUTTON -> {
                                    val shift = net.minecraft.client.gui.screens.Screen
                                        .hasShiftDown()
                                    val now = System.currentTimeMillis()
                                    val isDoubleClick =
                                        now - lastHeaderPressMillis < DOUBLE_CLICK_MS
                                    if (isDoubleClick) {
                                        // Quick second LMB → rename (overrides
                                        // selection — feels natural since user
                                        // is acting on this group specifically).
                                        editor.renamingGroup = group.id
                                        lastHeaderPressMillis = 0L
                                    } else {
                                        // First press → handle selection like
                                        // a node. Plain LMB replaces selection
                                        // with just this group; Shift+LMB adds
                                        // (or removes) it. Drag handler stays
                                        // active so the same press can also
                                        // initiate a move.
                                        if (shift) {
                                            editor.toggleGroupSelection(group.id)
                                        } else if (!editor.isGroupSelected(group.id)) {
                                            // Plain LMB on an *unselected*
                                            // group replaces the selection.
                                            // If already selected, leave the
                                            // selection intact so the drag
                                            // that follows moves every
                                            // selected item uniformly
                                            // (same rule as NodeCard).
                                            editor.clearSelection()
                                            editor.toggleGroupSelection(group.id)
                                        }
                                        lastHeaderPressMillis = now
                                    }
                                }
                                RIGHT_BUTTON -> {
                                    // Right-click on header → open group context menu.
                                    if (canvas != null) {
                                        val worldX = (bbox.minX - pad) + x
                                        val worldY = (bbox.minY - pad - GROUP_HEADER_HEIGHT) + y
                                        val screenX = ((worldX + canvas.panX) * canvas.zoom).toInt()
                                        val screenY = ((worldY + canvas.panY) * canvas.zoom).toInt()
                                        editor.openGroupMenu(screenX, screenY, group.id)
                                    }
                                }
                            }
                            true
                        }
                        else -> false
                    }
                },
            style = SurfaceStyle(
                color = NwTheme.colors.surfacePressed,
                shape = NwTheme.shapes.small,
                border = null,
                padding = PaddingValues(
                    horizontal = NwTheme.dimens.space6,
                    vertical = NwTheme.dimens.space2,
                ),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.Center,
                    horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                ) {
                    // Hide the name while the transparent GroupLabelOverlay is
                    // editing it, so the two don't stack. The template chip and
                    // collapse button stay visible.
                    if (editor.renamingGroup != group.id) {
                        Text(
                            group.name.ifBlank { "Group" },
                            style = NwTheme.typography.caption,
                        )
                    }
                    if (group.templateFile != null) {
                        Text(
                            "↪${group.templateFile}",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                    }
                }
                CollapseToggleButton(group.id, "−")
            }
        }
    }
}

/**
 * Small "−" / "+" pill in the group header strip. Press toggles the
 * group's collapsed state. The button consumes Press so the surrounding
 * header doesn't fire its own drag / rename / context-menu handlers.
 */
@Composable
internal fun CollapseToggleButton(groupId: GroupId, glyph: String) {
    val editor = LocalEditorState.current ?: return
    Surface(
        modifier = Modifier
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press && ev.button == LEFT_BUTTON) {
                    editor.toggleCollapsed(groupId)
                    true
                } else false
            },
        style = SurfaceStyle(
            color = NwTheme.colors.surfaceHover,
            shape = NwTheme.shapes.small,
            border = null,
            padding = PaddingValues(horizontal = NwTheme.dimens.space4, vertical = 0),
        ),
    ) {
        Text(glyph, style = NwTheme.typography.caption)
    }
}

internal const val GROUP_HEADER_HEIGHT = 14
private const val RIGHT_BUTTON = 1
private const val LEFT_BUTTON = 0
private const val DOUBLE_CLICK_MS = 300L
