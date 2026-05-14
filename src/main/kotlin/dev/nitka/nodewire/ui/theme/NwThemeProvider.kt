package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.nitka.nodewire.ui.feedback.ToastHost
import dev.nitka.nodewire.ui.overlay.OverlayHost
import net.minecraft.client.Minecraft

/**
 * Root of any Nodewire UI tree. Installs theme tokens, the MC font handle,
 * and an [OverlayHost] (so [Popup] / [Dialog] / [Tooltip] work without
 * extra wrapping). [LocalScreenSize] is provided by [NwComposeScreen]
 * higher up and reaches this scope transparently.
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
    ) {
        // Order: ToastHost outside OverlayHost so toasts render on top of
        // popups (a confirmation dialog still being open shouldn't hide an
        // error toast firing underneath it).
        ToastHost {
            OverlayHost(content = content)
        }
    }
}
