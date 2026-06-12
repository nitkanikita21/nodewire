package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Modal channel picker. Used by Channel Link Tool to ask which output
 * (on the source) or input (on the target) the user wants to wire.
 *
 * Renders centered on the screen with a small list of name + type-chip
 * rows. Click a row → [onPick] fires with the chosen channel name and
 * the screen closes. Press ESC or click outside the panel → cancel.
 */
class ChannelPickerScreen(
    private val title: String,
    private val options: List<Option>,
    private val onPick: (name: String) -> Unit,
) : NwComposeScreen(Component.literal(title)) {

    /** [name] is the pin/channel id handed back to [onPick]; [label] is what
     *  the row shows (defaults to the id). */
    data class Option(val name: String, val type: PinType, val label: String = name)

    @Composable
    override fun Content() {
        NwThemeProvider {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Outside-click cancels — Press anywhere unconsumed
                    // falls through to this Box and dismisses.
                    .pointerInput { ev, _, _ ->
                        if (ev is PointerEvent.Press) {
                            Minecraft.getInstance().setScreen(null)
                            true
                        } else false
                    },
            ) {
                Panel()
            }
        }
    }

    @Composable
    private fun Panel() {
        // Center via absolute positioning at half the screen minus half
        // a fixed panel width. Height is content-sized.
        val mc = Minecraft.getInstance()
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val panelWidth = PANEL_WIDTH
        Box(
            modifier = Modifier
                .padding(start = ((w - panelWidth) / 2), top = ((h - 160) / 2)),
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    // Eat clicks on the panel so they don't reach the
                    // outside-dismiss handler above.
                    .pointerInput { ev, _, _ -> ev is PointerEvent.Press },
                style = SurfaceStyle(
                    color = NwTheme.colors.surface,
                    shape = NwTheme.shapes.medium,
                    border = BorderStroke(1, NwTheme.colors.border),
                    padding = PaddingValues(NwTheme.dimens.space8),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                    Text(title, style = NwTheme.typography.subtitle)
                    if (options.isEmpty()) {
                        Text(
                            "(no channels)",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                    }
                    for (opt in options) OptionRow(opt) {
                        onPick(opt.name)
                        Minecraft.getInstance().setScreen(null)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(opt: ChannelPickerScreen.Option, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bg = if (hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onClick(); true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        // Small typed-color dot mirrors the pin handle so the row reads
        // the same as a graph row in the editor.
        Box(modifier = Modifier.size(6).background(pinColor(opt.type), NwTheme.shapes.medium))
        Text(opt.label, style = NwTheme.typography.caption)
        Text(
            opt.type.name.lowercase(),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}

@Composable
private fun pinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.REDSTONE -> NwTheme.colors.pinRedstone
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
    PinType.VIDEO -> NwTheme.colors.pinVideo
    PinType.ANY -> NwTheme.colors.pinAny
}

private const val PANEL_WIDTH = 220
