package dev.nitka.nodewire.ui.theme

import androidx.compose.runtime.Immutable
import dev.nitka.nodewire.ui.render.Color

/**
 * Layer-1 color tokens. Component-level styles ([ButtonStyle], etc.) read
 * from these via [NwTheme.colors] so a swap of [NwColors] re-skins the whole
 * UI. Domain-specific pin colors live here because they recur across many
 * components (nodes, palettes, type chips).
 */
@Immutable
data class NwColors(
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceHover: Color,
    val surfacePressed: Color,
    val overlay: Color,
    // Borders / dividers
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    // Text on surface
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceDisabled: Color,
    // Brand / state
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    // Domain-specific (pin types)
    val pinBool: Color,
    val pinInt: Color,
    val pinFloat: Color,
    val pinString: Color,
    val pinVec2: Color,
    val pinVec3: Color,
    val pinQuat: Color,
) {
    companion object {
        val Dark = NwColors(
            background     = Color(0xB3_10_10_14.toInt()),
            surface        = Color(0xFF_1C_1C_22.toInt()),
            surfaceHover   = Color(0xFF_24_24_2C.toInt()),
            surfacePressed = Color(0xFF_18_18_1E.toInt()),
            overlay        = Color(0xCC_00_00_00.toInt()),
            border         = Color(0xFF_30_30_38.toInt()),
            borderStrong   = Color(0xFF_50_50_5C.toInt()),
            divider        = Color(0xFF_28_28_30.toInt()),
            onSurface         = Color(0xFF_E8_E8_EC.toInt()),
            onSurfaceMuted    = Color(0xFF_94_94_A0.toInt()),
            onSurfaceDisabled = Color(0xFF_54_54_60.toInt()),
            accent         = Color(0xFF_4A_9E_FF.toInt()),
            accentHover    = Color(0xFF_6A_B0_FF.toInt()),
            accentPressed  = Color(0xFF_30_88_E8.toInt()),
            onAccent       = Color(0xFF_FF_FF_FF.toInt()),
            danger  = Color(0xFF_E8_5C_5C.toInt()),
            warning = Color(0xFF_E8_B0_4A.toInt()),
            success = Color(0xFF_5C_C8_7A.toInt()),
            pinBool   = Color(0xFF_E8_5C_5C.toInt()),
            pinInt    = Color(0xFF_5C_C8_E8.toInt()),
            pinFloat  = Color(0xFF_E8_C8_5C.toInt()),
            pinString = Color(0xFF_F0_8A_4A.toInt()),
            pinVec2   = Color(0xFF_7C_E8_5C.toInt()),
            pinVec3  = Color(0xFF_AC_E8_5C.toInt()),
            pinQuat  = Color(0xFF_C8_7C_E8.toInt()),
        )
    }
}
