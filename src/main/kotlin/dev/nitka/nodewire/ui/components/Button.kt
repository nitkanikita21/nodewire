package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.Shape
import dev.nitka.nodewire.ui.theme.LocalContentColor
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Per-state colors + border + shape + padding for a [Button]. State swap is
 * a single `bg = when { ... }` in [Button], so themes only need to think
 * about colors, never re-wire the layout.
 */
data class ButtonStyle(
    val container: Color,
    val containerHover: Color,
    val containerPressed: Color,
    val containerDisabled: Color,
    val content: Color,
    val contentDisabled: Color,
    val border: BorderStroke?,
    val shape: Shape,
    val padding: PaddingValues,
)

/**
 * Standard button presets. Each is `@Composable` because defaults are read
 * from [NwTheme]. Mix-and-match by `.copy(...)` of any returned style.
 */
object ButtonDefaults {
    @Composable
    fun filled() = ButtonStyle(
        container         = NwTheme.colors.accent,
        containerHover    = NwTheme.colors.accentHover,
        containerPressed  = NwTheme.colors.accentPressed,
        containerDisabled = NwTheme.colors.surfaceHover,
        content           = NwTheme.colors.onAccent,
        contentDisabled   = NwTheme.colors.onSurfaceDisabled,
        border  = null,
        shape   = NwTheme.shapes.medium,
        padding = PaddingValues(horizontal = NwTheme.dimens.space12, vertical = NwTheme.dimens.space6),
    )

    @Composable
    fun outlined() = ButtonStyle(
        container         = Color.Transparent,
        containerHover    = NwTheme.colors.surfaceHover,
        containerPressed  = NwTheme.colors.surfacePressed,
        containerDisabled = Color.Transparent,
        content           = NwTheme.colors.onSurface,
        contentDisabled   = NwTheme.colors.onSurfaceDisabled,
        border  = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border),
        shape   = NwTheme.shapes.medium,
        padding = PaddingValues(horizontal = NwTheme.dimens.space12, vertical = NwTheme.dimens.space6),
    )

    @Composable
    fun ghost() = ButtonStyle(
        container         = Color.Transparent,
        containerHover    = NwTheme.colors.surfaceHover,
        containerPressed  = NwTheme.colors.surfacePressed,
        containerDisabled = Color.Transparent,
        content           = NwTheme.colors.onSurface,
        contentDisabled   = NwTheme.colors.onSurfaceDisabled,
        border  = null,
        shape   = NwTheme.shapes.medium,
        padding = PaddingValues(horizontal = NwTheme.dimens.space12, vertical = NwTheme.dimens.space6),
    )

    @Composable
    fun danger() = ButtonStyle(
        container         = NwTheme.colors.danger,
        containerHover    = NwTheme.colors.danger.shiftLightness(0.06f),
        containerPressed  = NwTheme.colors.danger.shiftLightness(-0.06f),
        containerDisabled = NwTheme.colors.surfaceHover,
        content           = NwTheme.colors.onAccent,
        contentDisabled   = NwTheme.colors.onSurfaceDisabled,
        border  = null,
        shape   = NwTheme.shapes.medium,
        padding = PaddingValues(horizontal = NwTheme.dimens.space12, vertical = NwTheme.dimens.space6),
    )
}

/**
 * Pressable surface. Tracks hover and pressed state internally and selects
 * the appropriate container color from [style]. [content] is rendered with
 * [LocalContentColor] set so any [Text] inside picks up the foreground
 * color without an explicit `style.color` argument.
 *
 * `onClick` fires on Release inside the button — the press-then-release-
 * outside path (drag out and release) does NOT fire. Hover-out during a
 * press visually un-presses but a subsequent release inside fires `onClick`
 * because we don't clear the press state on hover-out.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonDefaults.filled(),
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    val container = when {
        !enabled -> style.containerDisabled
        pressed  -> style.containerPressed
        hovered  -> style.containerHover
        else     -> style.container
    }
    val contentColor = if (enabled) style.content else style.contentDisabled

    val pointerMod = modifier
        .onHover { hovered = it }
        .pointerInput { ev, _, _ ->
            when (ev) {
                is PointerEvent.Press -> {
                    if (enabled) pressed = true
                    enabled
                }
                is PointerEvent.Release -> {
                    val wasPressed = pressed
                    pressed = false
                    if (enabled && wasPressed && hovered) onClick()
                    true
                }
                else -> false
            }
        }

    Surface(
        modifier = pointerMod,
        style = SurfaceStyle(container, style.shape, style.border, style.padding),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
