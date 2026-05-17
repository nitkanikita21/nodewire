package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val h = (bbox.maxY - bbox.minY).toInt() + pad * 2 + HEADER_HEIGHT

    Box(
        modifier = Modifier
            .absolutePosition((bbox.minX - pad).toInt(), (bbox.minY - pad - HEADER_HEIGHT).toInt())
            .size(w, h)
            .background(NwTheme.colors.surfaceHover)
            .border(BorderStroke(1, NwTheme.colors.border), NwTheme.shapes.medium),
    ) {
        Surface(
            modifier = Modifier
                .size(w, HEADER_HEIGHT)
                .pointerInput { ev, _, _ ->
                    when (ev) {
                        is PointerEvent.Drag -> {
                            val zoom = canvas?.zoom ?: 1f
                            editor.moveGroup(group.id, ev.deltaX / zoom, ev.deltaY / zoom)
                            true
                        }
                        is PointerEvent.Press -> true
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
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                Text(group.name, style = NwTheme.typography.caption)
                if (group.templateFile != null) {
                    Text(
                        "↪${group.templateFile}",
                        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                    )
                }
            }
        }
    }
}

private const val HEADER_HEIGHT = 14
