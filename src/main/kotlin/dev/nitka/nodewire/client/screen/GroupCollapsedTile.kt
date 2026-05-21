package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Single-tile rendering of a collapsed group. Proxy pin rows are derived
 * from current edges every recomposition — collapse / expand has no
 * effect on edges, only on what the user sees.
 *
 * Inputs appear on the left column, outputs on the right. Rows are paired
 * by index; when one side has fewer entries an empty spacer fills the slot
 * so the opposing handle stays vertically aligned.
 */
@Composable
fun GroupCollapsedTile(group: Group) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    val edges by editor.edges.collectAsState()
    val nodeIds by editor.nodes.collectAsState()
    val allGroupsList by editor.groups.collectAsState()
    val allGroupsById = remember(allGroupsList) { allGroupsList.associateBy { it.id } }
    val closure = remember(group, nodeIds, allGroupsById) {
        GroupProxyPins.memberClosure(group, allGroupsById)
    }
    val proxies = remember(group, edges, closure) {
        GroupProxyPins.compute(editor.graph, group, closure)
    }
    val inputs = proxies.filter { it.side == PinSide.Input }
    val outputs = proxies.filter { it.side == PinSide.Output }
    val w = group.collapsedSize?.first ?: TILE_WIDTH
    val selected = editor.isGroupSelected(group.id)
    val borderColor = if (selected) NwTheme.colors.accent else NwTheme.colors.border
    val borderWidth = if (selected) 2 else 1
    var lastHeaderPressMillis by androidx.compose.runtime.remember { mutableStateOf(0L) }

    Box(
        modifier = Modifier
            .absolutePosition(group.pos.x.toInt(), group.pos.y.toInt())
            .width(w)
            .background(NwTheme.colors.surface)
            .border(BorderStroke(borderWidth, borderColor), NwTheme.shapes.small)
            .pointerInput { ev, x, y ->
                when (ev) {
                    is PointerEvent.Drag -> {
                        val zoom = canvas?.zoom ?: 1f
                        val dxW = ev.deltaX / zoom
                        val dyW = ev.deltaY / zoom
                        if (editor.isGroupSelected(group.id)) {
                            editor.moveSelected(dxW, dyW)
                        } else {
                            editor.moveGroup(group.id, dxW, dyW)
                        }
                        true
                    }
                    is PointerEvent.Press -> {
                        when (ev.button) {
                            0 -> {
                                val shift = net.minecraft.client.gui.screens.Screen
                                    .hasShiftDown()
                                val now = System.currentTimeMillis()
                                val isDouble = now - lastHeaderPressMillis < 300L
                                if (isDouble) {
                                    editor.renamingGroup = group.id
                                    lastHeaderPressMillis = 0L
                                } else {
                                    if (shift) {
                                        editor.toggleGroupSelection(group.id)
                                    } else if (!editor.isGroupSelected(group.id)) {
                                        editor.clearSelection()
                                        editor.toggleGroupSelection(group.id)
                                    }
                                    lastHeaderPressMillis = now
                                }
                            }
                            RIGHT_BUTTON -> if (canvas != null) {
                                val worldX = group.pos.x + x
                                val worldY = group.pos.y + y
                                val screenX = ((worldX + canvas.panX) * canvas.zoom).toInt()
                                val screenY = ((worldY + canvas.panY) * canvas.zoom).toInt()
                                editor.openGroupMenu(screenX, screenY, group.id)
                            }
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            // Header bar — same style as GroupFrame
            Surface(
                modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            group.name.ifBlank { "Group" },
                            style = NwTheme.typography.caption,
                        )
                        if (group.templateFile != null) {
                            Text(
                                "↪${group.templateFile}",
                                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                            )
                        }
                    }
                    CollapseToggleButton(group.id, "+")
                }
            }
            // Pin rows — one row per slot, inputs left, outputs right
            val rowCount = maxOf(inputs.size, outputs.size)
            for (i in 0 until rowCount) {
                val ip = inputs.getOrNull(i)
                val op = outputs.getOrNull(i)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NwTheme.dimens.space4),
                    verticalAlignment = Alignment.Center,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (ip != null) ProxyPinHandle(ip, group.id) else Box(modifier = Modifier.size(8, 8))
                    if (op != null) ProxyPinHandle(op, group.id) else Box(modifier = Modifier.size(8, 8))
                }
            }
        }
    }
}

@Composable
private fun ProxyPinHandle(proxy: GroupProxyPin, groupId: GroupId) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    when (proxy.side) {
        PinSide.Input -> Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        ) {
            PinDot(proxy, canvas?.originX ?: 0, canvas?.originY ?: 0, editor)
            Text(proxy.label, style = NwTheme.typography.caption)
        }
        PinSide.Output -> Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedByEnd(NwTheme.dimens.space2),
        ) {
            Text(proxy.label, style = NwTheme.typography.caption)
            PinDot(proxy, canvas?.originX ?: 0, canvas?.originY ?: 0, editor)
        }
    }
}

@Composable
private fun PinDot(
    proxy: GroupProxyPin,
    originX: Int,
    originY: Int,
    editor: EditorState,
) {
    Box(
        modifier = Modifier
            .size(PIN_DOT_SIZE)
            .background(proxyPinColor(proxy.type), NwTheme.shapes.medium)
            .onPositioned { coords ->
                editor.pinPositions.set(
                    PinKey(proxy.innerNode, proxy.innerPin, proxy.side),
                    (coords.centerX - originX).toFloat(),
                    (coords.centerY - originY).toFloat(),
                )
            },
    )
}

@Composable
private fun proxyPinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.REDSTONE -> NwTheme.colors.pinRedstone
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
    PinType.ANY -> NwTheme.colors.pinAny
}

internal const val TILE_WIDTH = 140
private const val PIN_DOT_SIZE = 8
private const val RIGHT_BUTTON = 1
