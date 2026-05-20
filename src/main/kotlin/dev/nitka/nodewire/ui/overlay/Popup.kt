package dev.nitka.nodewire.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.IntSize
import dev.nitka.nodewire.ui.modifier.input.onSizeChanged
import dev.nitka.nodewire.ui.theme.LocalScreenSize

/**
 * The portal primitive for everything overlay-y: tooltips, dropdowns,
 * context menus, modals. Registers [content] with the surrounding
 * [OverlayHost], computing the final screen position from [position] +
 * the popup's own measured size + the current screen size each frame.
 *
 * Implementation:
 *   * The popup tracks its own measured size via [onSizeChanged] wrapped
 *     around [content]. First frame uses size = (0,0) — the popup appears
 *     at the strategy's "raw" position, then the next frame re-positions
 *     with the real size (one-frame flicker on first show, acceptable).
 *   * Edge-flipping: if the strategy is `Anchored(Above|Below)` and the
 *     popup wouldn't fit on that side, it flips to the opposite side.
 *   * Edge-clamping: the final `(x, y)` is clamped to keep the popup
 *     fully on-screen if possible.
 *
 * [scrim] / [dismissOnClickOutside] / [onDismissRequest] are forwarded to
 * the [PopupEntry] and honored by [OverlayHost]. Use them for [Dialog];
 * leave them off for [Tooltip] / dropdown.
 */
@Composable
fun Popup(
    position: PopupPosition,
    scrim: Boolean = false,
    dismissOnClickOutside: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val overlay = LocalOverlay.current
    val screen = LocalScreenSize.current
    val id = remember { Any() }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Pre-measurement: render off-screen so the very first frame doesn't
    // show the popup at the strategy's raw position (which for LeftOf /
    // Above / etc. coincides with — or overlaps — the anchor). The next
    // frame, with real size, computes the correct position. One frame
    // invisible is preferable to one frame of visible overlap.
    val (x, y) = if (size == IntSize.Zero) -10000 to -10000
                 else resolveOffset(position, screen, size)

    // Push / update the entry every recomposition. The content lambda is
    // wrapped to measure itself via onSizeChanged — the wrapping Box has
    // zero overhead at layout (no own size constraints).
    SideEffect {
        overlay.put(
            PopupEntry(
                id = id,
                x = x,
                y = y,
                scrim = scrim,
                dismissOnClickOutside = dismissOnClickOutside,
                onDismissRequest = onDismissRequest,
                content = {
                    Box(modifier = Modifier.onSizeChanged { size = it }) { content() }
                },
            )
        )
    }
    // Remove on dispose so leaving the screen / hiding the popup cleans up.
    DisposableEffect(id) {
        onDispose { overlay.remove(id) }
    }
}

/**
 * Strategy → concrete `(x, y)`. Anchored Above/Below flip if there's not
 * enough room on the chosen side AND there is on the opposite. Final
 * coordinates are clamped to keep the popup on-screen when possible.
 */
private fun resolveOffset(
    pos: PopupPosition,
    screen: IntSize,
    popup: IntSize,
): Pair<Int, Int> {
    val raw = when (pos) {
        is PopupPosition.AtScreen -> pos.x to pos.y
        is PopupPosition.Anchored -> resolveAnchored(pos, screen, popup)
        PopupPosition.Centered -> ((screen.width - popup.width) / 2) to ((screen.height - popup.height) / 2)
    }
    return clampToScreen(raw.first, raw.second, popup, screen)
}

private fun resolveAnchored(
    pos: PopupPosition.Anchored,
    screen: IntSize,
    popup: IntSize,
): Pair<Int, Int> {
    val a = pos.anchor
    val g = pos.gap
    return when (pos.placement) {
        PopupPlacement.Below -> {
            val belowY = a.screenBottom + g
            val flipY = a.screenY - g - popup.height
            val flip = belowY + popup.height > screen.height && flipY >= 0
            a.screenX to if (flip) flipY else belowY
        }
        PopupPlacement.Above -> {
            val aboveY = a.screenY - g - popup.height
            val flipY = a.screenBottom + g
            val flip = aboveY < 0 && flipY + popup.height <= screen.height
            a.screenX to if (flip) flipY else aboveY
        }
        PopupPlacement.RightOf -> {
            val rightX = a.screenRight + g
            val flipX = a.screenX - g - popup.width
            val flip = rightX + popup.width > screen.width && flipX >= 0
            (if (flip) flipX else rightX) to a.screenY
        }
        PopupPlacement.LeftOf -> {
            val leftX = a.screenX - g - popup.width
            val flipX = a.screenRight + g
            val flip = leftX < 0 && flipX + popup.width <= screen.width
            (if (flip) flipX else leftX) to a.screenY
        }
    }
}

private fun clampToScreen(x: Int, y: Int, popup: IntSize, screen: IntSize): Pair<Int, Int> {
    if (popup.width == 0 || popup.height == 0) return x to y // pre-measurement
    val maxX = (screen.width - popup.width).coerceAtLeast(0)
    val maxY = (screen.height - popup.height).coerceAtLeast(0)
    return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
}
