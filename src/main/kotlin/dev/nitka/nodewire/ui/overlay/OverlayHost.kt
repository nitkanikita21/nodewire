package dev.nitka.nodewire.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.FlushingSurfaceRenderer
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Top-level overlay manager. Renders [content] full-screen, then iterates
 * every registered [PopupEntry] as a sibling positioned via
 * [absolutePosition]. Popups paint after content because they appear
 * later in the parent Box's child list.
 *
 * Auto-installed by [NwThemeProvider] so every Compose screen has overlay
 * support without ceremony — callers just use [Popup] / [Dialog] / [Tooltip].
 *
 * Layering: each popup's outer Layout uses [FlushingSurfaceRenderer] which
 * flushes [GuiGraphics] before painting. Necessary because MC's text
 * batch is drawn last regardless of insertion order — without the flush
 * the user's text would visually appear above the popup background.
 *
 * Scrim handling: a popup with `scrim = true` registers a full-screen
 * sibling behind it with [NwTheme.colors.overlay] background that
 * consumes all pointer input (so clicks don't leak to content) and
 * triggers [PopupEntry.onDismissRequest] on Press if
 * `dismissOnClickOutside` is set.
 */
@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val state = remember { OverlayState() }
    CompositionLocalProvider(LocalOverlay provides state) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. User content fills the screen.
            Box(modifier = Modifier.fillMaxSize()) { content() }
            // 2. Popups render as out-of-flow absolutely-positioned siblings.
            //    `key` keeps state stable across reorder.
            for (popup in state.popups) {
                key(popup.id) { PopupSlot(popup) }
            }
        }
    }
}

@Composable
private fun PopupSlot(popup: PopupEntry) {
    if (popup.scrim) {
        ScrimBox(
            onDismissRequest = popup.onDismissRequest,
            dismissOnClickOutside = popup.dismissOnClickOutside,
            dim = true,
        )
    } else if (popup.dismissOnClickOutside && popup.onDismissRequest != null) {
        // Invisible click-catcher — needed for popups like context menus
        // that want outside-click dismissal but no dim overlay.
        ScrimBox(
            onDismissRequest = popup.onDismissRequest,
            dismissOnClickOutside = true,
            dim = false,
        )
    }
    // Use Layout directly with FlushingSurfaceRenderer so prior text-batch
    // contents get committed before the popup paints — otherwise main-tree
    // text would visually float over the popup.
    Layout(
        modifier = Modifier.absolutePosition(popup.x, popup.y),
        renderer = FlushingSurfaceRenderer,
    ) {
        popup.content()
    }
}

@Composable
private fun ScrimBox(
    onDismissRequest: (() -> Unit)?,
    dismissOnClickOutside: Boolean,
    dim: Boolean,
) {
    // When `dim` is true, scrim paints theme.overlay (dialog modals); when
    // false, it's an invisible click-catcher (context menus). Either way
    // it consumes Press so subsequent unconsumed-click handlers don't fire.
    val mod = Modifier
        .absolutePosition(0, 0)
        .fillMaxSize()
        .let { if (dim) it.background(NwTheme.colors.overlay) else it }
        .pointerInput { ev, _, _ ->
            when (ev) {
                is PointerEvent.Press -> {
                    if (dismissOnClickOutside) onDismissRequest?.invoke()
                    // Dim scrims (modals) consume the press so background
                    // never sees it. Non-dim scrims (context-menu catchers)
                    // dismiss AND let the press through, so RMB-on-another-
                    // target dismisses the open menu and opens the new one
                    // in a single gesture.
                    dim
                }
                is PointerEvent.Release -> dim
                else -> false
            }
        }
    Layout(
        modifier = mod,
        renderer = if (dim) FlushingSurfaceRenderer else dev.nitka.nodewire.ui.render.EmptyRenderer,
    )
}
