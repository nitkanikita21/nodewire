package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.integration.aeronautics.AeroBlockKind
import dev.nitka.nodewire.integration.aeronautics.AeroChannel
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
 * Modal channel picker for Aeronautics source blocks. Shown by the Channel
 * Link Tool when the user right-clicks an Aero BE so they can select which
 * [AeroChannel] to bind.
 *
 * Lists [AeroChannel.byKind] for [kind]. Each row shows the channel's
 * [displayName] and a pin-type dot + label mirroring [ChannelPickerScreen].
 * Click a row → [onPick] fires and the screen closes. ESC or outside-click
 * cancels.
 */
class AeroChannelPickerScreen(
    private val kind: AeroBlockKind,
    private val endpoint: EndpointRef,
    private val onPick: (EndpointRef, AeroBlockKind, AeroChannel) -> Unit,
) : NwComposeScreen(Component.literal("Select channel — ${kind.displayName}")) {

    private val channels: List<AeroChannel> = AeroChannel.byKind(kind)

    @Composable
    override fun Content() {
        NwThemeProvider {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
        val mc = Minecraft.getInstance()
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        Box(
            modifier = Modifier
                .padding(start = ((w - PANEL_WIDTH) / 2), top = ((h - 160) / 2)),
        ) {
            Surface(
                modifier = Modifier
                    .width(PANEL_WIDTH)
                    .pointerInput { ev, _, _ -> ev is PointerEvent.Press },
                style = SurfaceStyle(
                    color = NwTheme.colors.surface,
                    shape = NwTheme.shapes.medium,
                    border = BorderStroke(1, NwTheme.colors.border),
                    padding = PaddingValues(NwTheme.dimens.space8),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                    Text("${kind.displayName} — channel", style = NwTheme.typography.subtitle)
                    if (channels.isEmpty()) {
                        Text(
                            "(no channels)",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                    }
                    for (ch in channels) {
                        AeroChannelRow(ch) {
                            onPick(endpoint, kind, ch)
                            Minecraft.getInstance().setScreen(null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AeroChannelRow(channel: AeroChannel, onClick: () -> Unit) {
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
        Box(
            modifier = Modifier
                .size(6)
                .background(aeroChannelPinColor(channel.pinType), NwTheme.shapes.medium),
        )
        Text(channel.displayName, style = NwTheme.typography.caption)
        Text(
            channel.pinType.name.lowercase(),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        // RO / RW indicator — 🔓 means an external write would actually
        // stick (setter exists AND tick() doesn't overwrite); 🔒 means
        // the value is read-only because it's kinetic-network-driven or
        // computed every tick.
        Text(
            if (channel.writable) "🔓 rw" else "🔒 ro",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}

@Composable
private fun aeroChannelPinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.REDSTONE -> NwTheme.colors.pinRedstone
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
}

private const val PANEL_WIDTH = 260
