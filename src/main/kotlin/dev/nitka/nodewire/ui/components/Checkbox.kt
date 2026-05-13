package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Compact two-state toggle. Renders as a small square that fills with the
 * accent color when [checked] and shows an outline otherwise.
 *
 * Doesn't include a label — wrap in a Row with a [Text] if you want one
 * (lets callers control spacing + click-target shape, instead of baking
 * in an opinionated label placement here).
 *
 * No tri-state (indeterminate) yet — add a [CheckboxState] sealed class if
 * we hit a use case.
 */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var hovered by remember { mutableStateOf(false) }

    val container = when {
        !enabled -> NwTheme.colors.surfaceHover
        checked && hovered -> NwTheme.colors.accentHover
        checked -> NwTheme.colors.accent
        hovered -> NwTheme.colors.surfaceHover
        else -> Color.Transparent
    }
    val borderColor = when {
        !enabled -> NwTheme.colors.border
        checked -> Color.Transparent
        else -> NwTheme.colors.borderStrong
    }
    Box(
        modifier = modifier
            .size(CHECKBOX_SIZE)
            .background(container, NwTheme.shapes.medium)
            .border(BorderStroke(1, borderColor), NwTheme.shapes.medium)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Release && enabled) {
                    onCheckedChange(!checked)
                    true
                } else ev is PointerEvent.Press && enabled
            },
    )
    // Filled vs empty is the visual signal. A glyph would be nicer but the
    // canvas doesn't have vector paths or an icon system yet — fill with
    // accent for checked, hollow border for unchecked.
}

private const val CHECKBOX_SIZE = 10
