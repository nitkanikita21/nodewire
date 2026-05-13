package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeType
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.ui.canvas.CanvasState
import dev.nitka.nodewire.ui.components.Divider
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.IntSize
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.scroll.rememberScrollState
import dev.nitka.nodewire.ui.scroll.verticalScroll
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Left-docked node picker. Lists every [NodeType] in [NodeTypeRegistry]
 * grouped by [NodeCategory]. Clicking a row spawns the node at the centre
 * of the current viewport so the user doesn't have to chase it down.
 *
 * Positioned as an absolute child of the editor's outer Box; renders on
 * top of the NodeCanvas because it's mounted last in the child list.
 */
@Composable
fun Palette(
    canvas: CanvasState,
    screenSize: IntSize,
    onSpawn: (NodeType, CanvasPos) -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .absolutePosition(0, 0)
            .width(PALETTE_WIDTH)
            .height(screenSize.height.coerceAtLeast(1))
            .background(NwTheme.colors.surface)
            // Capture all pointer events so canvas pan / zoom doesn't fire
            // when the user is interacting with the palette.
            .pointerInput { ev, _, _ ->
                ev is PointerEvent.Press || ev is PointerEvent.Scroll
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenSize.height.coerceAtLeast(1))
                .verticalScroll(scroll)
                .padding(NwTheme.dimens.space8),
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("Nodes", style = NwTheme.typography.subtitle)
            Divider()
            val byCategory = NodeTypeRegistry.byCategory()
            for (category in NodeCategory.entries) {
                val types = byCategory[category] ?: continue
                CategoryHeader(category)
                for (type in types) {
                    PaletteRow(type) {
                        val worldX = (screenSize.width / 2f) / canvas.zoom - canvas.panX
                        val worldY = (screenSize.height / 2f) / canvas.zoom - canvas.panY
                        onSpawn(type, CanvasPos(worldX, worldY))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: NodeCategory) {
    Text(
        category.displayName.uppercase(),
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
    )
}

@Composable
private fun PaletteRow(type: NodeType, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        pressed -> NwTheme.colors.surfacePressed
        hovered -> NwTheme.colors.surfaceHover
        else -> NwTheme.colors.surface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Press -> {
                        pressed = true
                        true
                    }
                    is PointerEvent.Release -> {
                        val wasPressed = pressed
                        pressed = false
                        if (wasPressed && hovered) onClick()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(type.displayName, style = NwTheme.typography.caption)
    }
}

private const val PALETTE_WIDTH = 160
