package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import net.minecraft.client.gui.Font

/**
 * CompositionLocals for the four theme axes. `staticCompositionLocalOf`
 * (not `compositionLocalOf`) because tokens change very rarely — `static`
 * skips the dependency tracking on reads, so any composable accessing
 * [NwTheme.colors] etc. doesn't get re-invalidated on every theme tick.
 *
 * Defaults: [NwColors.Dark] palette + empty-default Dimens/Shapes/Typography.
 */
val LocalNwColors = staticCompositionLocalOf { NwColors.Dark }
val LocalNwDimens = staticCompositionLocalOf { NwDimens() }
val LocalNwShapes = staticCompositionLocalOf { NwShapes() }
val LocalNwTypography = staticCompositionLocalOf { NwTypography() }

/**
 * MC [Font] handle exposed through the composition. Provided by
 * [NwThemeProvider] so Text composables can read it without poking
 * `Minecraft.getInstance()` directly (testable in unit tests with a fake).
 */
val LocalFont = staticCompositionLocalOf<Font> {
    error("LocalFont not provided — wrap your content in NwThemeProvider")
}

/**
 * Foreground color cascade for [Text] and [Icon] children of a container
 * that wants to override the default `onSurface` color (e.g. [Button] sets
 * this to `onAccent` so a Text inside it shows in the right color without
 * needing an explicit `style.color` argument).
 *
 * Resolution order in Text: `style.color ?: LocalContentColor.current ?:
 * NwTheme.colors.onSurface`. Null means "inherit from the surrounding
 * theme's onSurface" — the default.
 */
val LocalContentColor =
    staticCompositionLocalOf<dev.nitka.nodewire.ui.render.Color?> { null }

/**
 * Single static accessor for all theme axes. Use as `NwTheme.colors.accent`,
 * `NwTheme.dimens.space8`, etc. The properties are `@ReadOnlyComposable` so
 * reading them doesn't bump invalidation scope.
 */
object NwTheme {
    val colors: NwColors
        @Composable @ReadOnlyComposable get() = LocalNwColors.current
    val dimens: NwDimens
        @Composable @ReadOnlyComposable get() = LocalNwDimens.current
    val shapes: NwShapes
        @Composable @ReadOnlyComposable get() = LocalNwShapes.current
    val typography: NwTypography
        @Composable @ReadOnlyComposable get() = LocalNwTypography.current
}
