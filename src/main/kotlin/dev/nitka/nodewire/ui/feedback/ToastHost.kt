package dev.nitka.nodewire.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.LocalScreenSize
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay

/**
 * Sonner-style notification overlay. Provides a [ToastManager] to [content]
 * via [LocalToastManager] and renders any live toasts in a bottom-right
 * stack.
 *
 * Auto-dismiss: each toast card kicks off a [LaunchedEffect] keyed to its
 * id; on first composition it sleeps for [Toast.durationMs] and removes
 * itself. Cancelled cleanly if the host leaves composition (screen close).
 *
 * Mount once at the root — [NwThemeProvider] does this automatically so
 * any composable inside the theme can grab the manager and call
 * `.success(...)` / `.danger(...)` etc.
 */
@Composable
fun ToastHost(content: @Composable () -> Unit) {
    val manager = remember { ToastManager() }
    CompositionLocalProvider(LocalToastManager provides manager) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            ToastStack(manager)
        }
    }
}

@Composable
private fun ToastStack(manager: ToastManager) {
    val screen = LocalScreenSize.current
    if (manager.toasts.isEmpty()) return
    // Position each toast individually at a computed (x, y). Newest toast
    // anchors at the bottom-right corner; older toasts stack upward.
    // Card heights are estimated (TOAST_SLOT_HEIGHT); fine because each
    // toast renders a single text line at known font height.
    val x = (screen.width - TOAST_WIDTH - EDGE_GAP).coerceAtLeast(0)
    val toasts = manager.toasts
    Box(modifier = Modifier.absolutePosition(0, 0).fillMaxSize()) {
        toasts.forEachIndexed { index, toast ->
            // Index 0 = oldest; last = newest. Newest sits closest to the
            // corner so the most recent message gets prime real estate.
            val fromBottom = toasts.size - index
            val y = (screen.height - fromBottom * (TOAST_SLOT_HEIGHT + STACK_GAP) - EDGE_GAP)
                .coerceAtLeast(0)
            key(toast.id) {
                Box(modifier = Modifier.absolutePosition(x, y)) {
                    ToastCard(toast, onDismiss = { manager.dismiss(toast.id) })
                }
            }
        }
    }
}

@Composable
private fun ToastCard(toast: Toast, onDismiss: () -> Unit) {
    val accent = toast.variant.color(NwTheme.colors)
    LaunchedEffect(toast.id) {
        delay(toast.durationMs)
        onDismiss()
    }
    Surface(
        modifier = Modifier.width(TOAST_WIDTH),
        style = SurfaceStyle(
            color = NwTheme.colors.surface,
            shape = NwTheme.shapes.medium,
            border = BorderStroke(1, NwTheme.colors.border),
            padding = PaddingValues(NwTheme.dimens.space8),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8),
        ) {
            // Left accent stripe — a small filled square in the variant
            // color so the user can scan toasts by tone at a glance.
            Box(
                modifier = Modifier
                    .size(STRIPE_WIDTH, STRIPE_HEIGHT)
                    .background(accent, NwTheme.shapes.medium),
            )
            Text(
                toast.variant.glyph,
                style = NwTheme.typography.body.copy(color = accent),
            )
            Text(
                toast.message,
                style = NwTheme.typography.caption,
            )
        }
    }
}

private const val TOAST_WIDTH = 220

/**
 * Reserved vertical slot per toast. Real height varies slightly with the
 * font / wrap behaviour, but a fixed slot keeps the bottom-up stack
 * positions deterministic without measuring every card.
 */
private const val TOAST_SLOT_HEIGHT = 30
private const val EDGE_GAP = 12
private const val STACK_GAP = 6
private const val STRIPE_WIDTH = 3
private const val STRIPE_HEIGHT = 18
