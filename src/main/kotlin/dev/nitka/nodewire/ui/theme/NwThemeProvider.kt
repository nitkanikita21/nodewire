package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import net.minecraft.client.Minecraft

/**
 * Root of any Nodewire UI tree. Installs theme tokens + the MC font handle
 * as CompositionLocals so descendants can read [NwTheme.colors] etc.
 *
 * Defaults to the [NwColors.Dark] palette. Pass alternate tokens to skin a
 * subtree (e.g. inside a popover that wants a different background).
 */
@Composable
fun NwThemeProvider(
    colors: NwColors = NwColors.Dark,
    dimens: NwDimens = NwDimens(),
    shapes: NwShapes = NwShapes(),
    typography: NwTypography = NwTypography(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNwColors provides colors,
        LocalNwDimens provides dimens,
        LocalNwShapes provides shapes,
        LocalNwTypography provides typography,
        LocalFont provides Minecraft.getInstance().font,
        content = content,
    )
}
