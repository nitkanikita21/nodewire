package dev.nitka.nodewire.ui.feedback

import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwColors

/**
 * Visual flavour of a [Toast]. Drives the accent stripe color and the
 * leading glyph; the rest of the card is theme-neutral.
 */
enum class ToastVariant(val glyph: String) {
    Success("✓"),
    Info("i"),
    Warning("!"),
    Danger("✕"),
    ;

    /** Resolves the variant's accent color from the active theme palette. */
    fun color(colors: NwColors): Color = when (this) {
        Success -> colors.success
        Info -> colors.accent
        Warning -> colors.warning
        Danger -> colors.danger
    }
}

/**
 * One queued / live notification. [id] is stable across recompositions
 * so [ToastHost] can key its UI to it; [createdAt] is the wall-clock
 * timestamp used by the auto-dismiss effect.
 */
data class Toast(
    val id: Long,
    val message: String,
    val variant: ToastVariant,
    val durationMs: Long,
)
