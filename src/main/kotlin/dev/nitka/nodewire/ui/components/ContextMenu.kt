package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * One row in a [ContextMenu]. Sealed because the renderer needs to know
 * which variant to draw — actions get a click handler, submenus get an
 * expand chevron and open on hover, separators draw a thin divider.
 */
sealed interface ContextMenuItem {
    data class Action(val label: String, val onClick: () -> Unit) : ContextMenuItem
    data class Submenu(val label: String, val items: List<ContextMenuItem>) : ContextMenuItem
    data object Separator : ContextMenuItem
}

/**
 * Right-click style overlay menu. Opens at [position]; clicking outside
 * or on an action item fires [onDismiss]. Submenus open on hover and
 * anchor to the right of their row (edge-flipping handled by [Popup]).
 *
 * No keyboard navigation yet — add when we need it.
 */
@Composable
fun ContextMenu(
    items: List<ContextMenuItem>,
    position: PopupPosition,
    onDismiss: () -> Unit,
) {
    Popup(
        position = position,
        dismissOnClickOutside = true,
        onDismissRequest = onDismiss,
    ) {
        MenuPanel {
            for (item in items) Item(item, onDismiss)
        }
    }
}

@Composable
private fun MenuPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.width(MENU_WIDTH),
        style = SurfaceStyle(
            color = NwTheme.colors.surface,
            shape = NwTheme.shapes.medium,
            border = BorderStroke(1, NwTheme.colors.border),
            padding = PaddingValues(horizontal = 0, vertical = NwTheme.dimens.space4),
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0)) {
            content()
        }
    }
}

@Composable
private fun Item(item: ContextMenuItem, onDismiss: () -> Unit) {
    when (item) {
        is ContextMenuItem.Action -> ActionRow(item.label) {
            item.onClick()
            onDismiss()
        }
        is ContextMenuItem.Submenu -> SubmenuRow(item, onDismiss)
        ContextMenuItem.Separator -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1)
                .padding(vertical = NwTheme.dimens.space2)
                .background(NwTheme.colors.divider),
        )
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
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
            .padding(horizontal = NwTheme.dimens.space8, vertical = NwTheme.dimens.space4)
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
    ) {
        Text(label, style = NwTheme.typography.caption)
    }
}

@Composable
private fun SubmenuRow(item: ContextMenuItem.Submenu, onDismiss: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bg = if (hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = NwTheme.dimens.space8, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it }
            .onPositioned { anchor = it },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Center,
        ) {
            Text(item.label, style = NwTheme.typography.caption)
            // ▸ chevron — plain text so we don't depend on an icon system.
            Text("›", style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
        }
    }
    val anchorCoords = anchor
    if (hovered && anchorCoords != null) {
        Popup(
            position = PopupPosition.Anchored(
                anchor = anchorCoords,
                placement = PopupPlacement.RightOf,
                gap = 2,
            ),
            // Submenu is part of the parent's hover lifetime — no own dismiss.
            // Closes naturally when hover leaves the parent row, which clears
            // [hovered] and stops re-emitting the Popup entry.
        ) {
            MenuPanel {
                for (sub in item.items) Item(sub, onDismiss)
            }
        }
    }
}

private const val MENU_WIDTH = 150
