package dev.nitka.nodewire.client.screen

import net.minecraft.client.renderer.ShaderInstance

/**
 * Holds the registered `nodewire:screen_crt` core shader instance (set by the
 * RegisterShadersEvent handler in NodewireClient). The Vista-style CRT look
 * for clean-signal feeds: curvature, scanlines, phosphor triads, vignette.
 * Null until registration runs or if compilation fails — screens then fall
 * back to the flat position_tex_color blit.
 */
object ScreenCrtShader {
    @JvmStatic
    var instance: ShaderInstance? = null
}
