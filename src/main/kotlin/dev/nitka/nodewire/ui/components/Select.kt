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
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Single-select dropdown — click the resting field to expand a list of
 * [options]; click a row to commit and close. Outside-click dismisses
 * via the [Popup]'s `dismissOnClickOutside` (no-dim scrim handled by
 * OverlayHost).
 *
 * Compact density matches [TextInput] / cycle controls: flat fill, no
 * border at rest, accent border when "open".
 */
@Composable
fun <T> Select(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hovered by remember { mutableStateOf(false) }

    val bg = when {
        open -> NwTheme.colors.surfacePressed
        hovered -> NwTheme.colors.surfacePressed
        else -> NwTheme.colors.surfaceHover
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            .onHover { hovered = it }
            .onPositioned { anchor = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { open = !open; true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label(selected), style = NwTheme.typography.caption)
        // ▾ chevron to signal "this opens".
        Text(
            "▾",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }

    val a = anchor
    if (open && a != null) {
        Popup(
            position = PopupPosition.Anchored(anchor = a, placement = PopupPlacement.Below, gap = 1),
            dismissOnClickOutside = true,
            onDismissRequest = { open = false },
        ) {
            Surface(
                modifier = Modifier.width(a.width.coerceAtLeast(60)),
                style = SurfaceStyle(
                    color = NwTheme.colors.surface,
                    shape = NwTheme.shapes.medium,
                    border = null,
                    padding = PaddingValues(horizontal = 0, vertical = NwTheme.dimens.space2),
                ),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(0),
                    horizontalAlignment = Alignment.Stretch,
                ) {
                    for (opt in options) {
                        OptionRow(
                            label = label(opt),
                            isSelected = opt == selected,
                            onClick = {
                                onSelect(opt)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        hovered -> NwTheme.colors.surfaceHover
        isSelected -> NwTheme.colors.surfacePressed
        else -> NwTheme.colors.surface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onClick(); true } else false
            },
    ) {
        Text(label, style = NwTheme.typography.caption)
    }
}
