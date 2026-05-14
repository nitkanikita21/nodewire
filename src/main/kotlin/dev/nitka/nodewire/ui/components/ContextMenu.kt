package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.theme.LocalScreenSize
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
    val screen = LocalScreenSize.current
    // Decide the submenu growth direction once. Heuristic: if the user
    // opened the menu in the right half of the screen, grow submenus left;
    // otherwise right. Keeps the whole cascade in one direction.
    val anchorX = when (position) {
        is PopupPosition.AtScreen -> position.x
        is PopupPosition.Anchored -> position.anchor.screenX
        PopupPosition.Centered -> screen.width / 2
    }
    val direction =
        if (anchorX > screen.width / 2) PopupPlacement.LeftOf else PopupPlacement.RightOf

    Popup(
        position = position,
        dismissOnClickOutside = true,
        onDismissRequest = onDismiss,
    ) {
        CompositionLocalProvider(LocalSubmenuDirection provides direction) {
            MenuPanel {
                for (item in items) Item(item, onDismiss)
            }
        }
    }
}

/**
 * Hover reporter handed to child [SubmenuRow]s via [LocalSubmenuOpenReporter]
 * so they can let the enclosing [MenuPanel] know when their own popup is
 * open. Keyed by a per-row id because a panel can host multiple submenu
 * rows — if they all reported into a single boolean, the last writer
 * would clobber an earlier "true". With a map, the panel ORs all rows
 * together.
 */
private val LocalSubmenuOpenReporter = compositionLocalOf<((id: Any, open: Boolean) -> Unit)?> { null }

/**
 * Direction every submenu in the current [ContextMenu] tree grows toward.
 * Picked once at the root from the click position so submenus consistently
 * cascade right (when menu opened in left half of screen) or left (right
 * half). Without this, each submenu's [Popup] flip decision is independent
 * — a wide submenu would flip while its narrow sibling stays put, looking
 * jittery.
 */
private val LocalSubmenuDirection = compositionLocalOf { PopupPlacement.RightOf }

/**
 * [onHover] fires `true` whenever this panel OR any descendant submenu
 * (at any depth) has hover, and `false` only when the whole sub-tree is
 * cold. Callers use this to keep their parent submenu open while the
 * user is navigating deeper into the chain.
 */
/**
 * `onSelfHover` is the panel's own hover (wired DIRECTLY to the onHover
 * modifier — no SideEffect indirection so it fires synchronously inside
 * the hover walk; critical for the parent SubmenuRow's open-or-not
 * decision in the same frame the cursor enters the panel).
 *
 * `onDescendantOpen` fires when any nested submenu's popup is open —
 * collected via the [LocalSubmenuOpenReporter] map and forwarded once
 * per composition through a SideEffect (acceptable because the popup
 * is already open when this changes; no chicken-and-egg with mounting).
 */
@Composable
private fun MenuPanel(
    onSelfHover: (Boolean) -> Unit = {},
    onDescendantOpen: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val openChildren = remember { mutableStateMapOf<Any, Boolean>() }
    val descendantOpen = openChildren.values.any { it }
    SideEffect { onDescendantOpen(descendantOpen) }

    Surface(
        modifier = Modifier.onHover(onSelfHover),
        style = SurfaceStyle(
            color = NwTheme.colors.surface,
            shape = NwTheme.shapes.medium,
            border = BorderStroke(1, NwTheme.colors.border),
            padding = PaddingValues(horizontal = 0, vertical = NwTheme.dimens.space4),
        ),
    ) {
        // Stretch makes every row fill the column's cross-axis (= the
        // widest row's intrinsic content width). No fixed panel width —
        // the menu auto-sizes to its widest label.
        CompositionLocalProvider(
            LocalSubmenuOpenReporter provides { id, open -> openChildren[id] = open },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(0),
                horizontalAlignment = Alignment.Stretch,
            ) {
                content()
            }
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
    var rowHovered by remember { mutableStateOf(false) }
    // Split into two independent states so the submenu's panel hover and
    // its descendants' state cascade through different code paths.
    // `subPanelHovered` is wired DIRECTLY from the submenu's onHover
    // modifier — fires synchronously during the hover walk, which lets
    // us see "cursor moved into the panel" in the very same recomposition
    // that sees "cursor left the row". `subDescendantOpen` arrives later
    // via SideEffect, fine because the panel is already mounted by then.
    var subPanelHovered by remember { mutableStateOf(false) }
    var subDescendantOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val rowId = remember { Any() }
    val open = rowHovered || subPanelHovered || subDescendantOpen
    val reporter = LocalSubmenuOpenReporter.current
    SideEffect { reporter?.invoke(rowId, open) }
    val bg = if (open) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = NwTheme.dimens.space8, vertical = NwTheme.dimens.space4)
            .onHover { rowHovered = it }
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
    val placement = LocalSubmenuDirection.current
    if (open && anchorCoords != null) {
        Popup(
            position = PopupPosition.Anchored(
                anchor = anchorCoords,
                placement = placement,
                gap = 0,
            ),
        ) {
            MenuPanel(
                onSelfHover = { subPanelHovered = it },
                onDescendantOpen = { subDescendantOpen = it },
            ) {
                for (sub in item.items) Item(sub, onDismiss)
            }
        }
    }
}

