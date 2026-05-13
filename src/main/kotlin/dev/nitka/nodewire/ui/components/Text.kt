package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.render.TextRenderer
import dev.nitka.nodewire.ui.theme.LocalContentColor
import dev.nitka.nodewire.ui.theme.LocalFont
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.TextStyle
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.appliedenergistics.yoga.YogaMeasureMode
import org.appliedenergistics.yoga.YogaSize

/**
 * Renders a single line of text. Style flags (bold/italic/underline/
 * strikethrough) are applied via MC's [ChatFormatting]. Width is measured
 * intrinsically via the MC font and clamped to Yoga's width constraint;
 * height is always `font.lineHeight * scale`.
 *
 * Color resolves to `style.color ?: NwTheme.colors.onSurface` at composition
 * time, since the renderer can't read CompositionLocals at paint time.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = NwTheme.typography.body,
) {
    val font = LocalFont.current
    // Color cascade: explicit > content-color from surrounding container > theme default.
    val color = style.color ?: LocalContentColor.current ?: NwTheme.colors.onSurface
    val component = remember(text, style.bold, style.italic, style.underline, style.strikethrough) {
        val styled = Component.literal(text)
        val flags = buildList {
            if (style.bold) add(ChatFormatting.BOLD)
            if (style.italic) add(ChatFormatting.ITALIC)
            if (style.underline) add(ChatFormatting.UNDERLINE)
            if (style.strikethrough) add(ChatFormatting.STRIKETHROUGH)
        }
        if (flags.isEmpty()) styled else styled.withStyle(*flags.toTypedArray())
    }
    val scale = style.scale
    Layout(
        modifier = modifier,
        renderer = TextRenderer(component, color, style.shadow, scale),
        yogaConfig = {
            setMeasureFunction { _, w, widthMode, _, _ ->
                val intrinsicW = font.width(component) * scale
                val width = when (widthMode) {
                    YogaMeasureMode.EXACTLY -> w
                    YogaMeasureMode.AT_MOST -> intrinsicW.coerceAtMost(w)
                    else -> intrinsicW
                }
                val height = font.lineHeight * scale
                YogaSize(width, height)
            }
        },
    )
}
