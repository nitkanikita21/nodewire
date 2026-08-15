package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.integration.tracksplus.TracksPlusTuning
import dev.nitka.nodewire.net.ApplyTrackTuningPacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.clickable
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.roundToInt

/**
 * The Track Tuning Key's editor: one slider per Tracks+ tuning knob
 * ([TracksPlusTuning.SPECS] carries the ranges/steps that mirror the mod's
 * own clamps). Values arrive pre-read from the SERVER (client BE copies
 * don't sync the multipliers); Apply writes the clicked track and Tracks+
 * itself mirrors each write along the CONNECTED chain.
 */
class TrackTuningScreen(
    private val pos: BlockPos,
    initial: CompoundTag,
) : NwComposeScreen(Component.literal("Track Tuning")) {

    private var values: Map<String, Double> by mutableStateOf(
        TracksPlusTuning.SPECS.associate { s ->
            s.key to (if (initial.contains(s.key)) initial.getDouble(s.key) else s.min).coerceIn(s.min, s.max)
        },
    )

    private fun set(key: String, v: Double) {
        val s = TracksPlusTuning.spec(key) ?: return
        val snapped = (v / s.step).roundToInt() * s.step
        values = values + (key to snapped.coerceIn(s.min, s.max))
    }

    private fun apply() {
        val tag = CompoundTag()
        for ((k, v) in values) tag.putDouble(k, v)
        PacketDistributor.sendToServer(ApplyTrackTuningPacket(pos, tag))
        onClose()
    }

    @Composable
    override fun Content() {
        NwThemeProvider {
            Column(
                modifier = Modifier.fillMaxSize().padding(NwTheme.dimens.space8),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.width(PANEL_W),
                    style = SurfaceStyle(
                        color = NwTheme.colors.surface,
                        shape = NwTheme.shapes.medium,
                        border = BorderStroke(1, NwTheme.colors.border),
                        padding = PaddingValues(NwTheme.dimens.space8),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Center,
                        ) {
                            Text("Track Tuning — connected chain", style = NwTheme.typography.subtitle)
                            Box(modifier = Modifier.weight(1f)) {}
                        }
                        Text(
                            "Applies to the whole connected track chain (this hull side).",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )

                        TracksPlusTuning.SPECS.forEach { s -> SliderRow(s) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Box(modifier = Modifier.weight(1f)) {}
                            Button(onClick = { onClose() }) { Text("Cancel") }
                            Button(onClick = { apply() }) { Text("Apply to chain") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SliderRow(s: TracksPlusTuning.Spec) {
        val v = values[s.key] ?: s.min
        Column(verticalArrangement = Arrangement.spacedBy(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                Text(s.label, style = NwTheme.typography.body)
                Box(modifier = Modifier.weight(1f)) {}
                Text(fmt(s, v), style = NwTheme.typography.body.copy(color = NwTheme.colors.accent))
            }
            Row(
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                StepButton("-") { set(s.key, v - s.step) }
                SliderTrack(s, v)
                StepButton("+") { set(s.key, v + s.step) }
            }
            Row(modifier = Modifier.width(SLIDER_W).padding(PaddingValues(STEP_W + 4, 0, STEP_W + 4, 0))) {
                Text(fmt(s, s.min), style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
                Box(modifier = Modifier.weight(1f)) {}
                Text(fmt(s, s.max), style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
            }
        }
    }

    @Composable
    private fun StepButton(label: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier.width(STEP_W).height(TRACK_H)
                .background(NwTheme.colors.surfaceHover, NwTheme.shapes.small)
                .clickable(onClick),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Center,
        ) {
            Text(label, style = NwTheme.typography.body)
        }
    }

    @Composable
    private fun SliderTrack(s: TracksPlusTuning.Spec, v: Double) {
        val frac = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
        val fillW = (frac * (SLIDER_W - 2 * STEP_W - 8)).roundToInt()
        val trackW = SLIDER_W - 2 * STEP_W - 8
        Box(
            modifier = Modifier.width(trackW).height(TRACK_H)
                .background(NwTheme.colors.surfaceHover, NwTheme.shapes.small)
                .pointerInput { ev, lx, _ ->
                    when (ev) {
                        is PointerEvent.Press -> if (ev.button == 0) { setFromX(s, lx, trackW); true } else false
                        is PointerEvent.Drag -> if (ev.button == 0) { setFromX(s, lx, trackW); true } else false
                        else -> false
                    }
                },
        ) {
            Box(
                modifier = Modifier.width(fillW.coerceAtLeast(2)).height(TRACK_H)
                    .background(NwTheme.colors.accent.copy(alpha = 0.55f), NwTheme.shapes.small),
            ) {}
        }
    }

    private fun setFromX(s: TracksPlusTuning.Spec, localX: Int, trackW: Int) {
        val frac = (localX.toDouble() / trackW).coerceIn(0.0, 1.0)
        set(s.key, s.min + frac * (s.max - s.min))
    }

    private fun fmt(s: TracksPlusTuning.Spec, v: Double): String =
        if (s.step >= 1.0) v.roundToInt().toString() else String.format("%.2f", v)

    companion object {
        private const val PANEL_W = 320
        private const val SLIDER_W = 296
        private const val STEP_W = 16
        private const val TRACK_H = 12

        /** Client entry from [dev.nitka.nodewire.net.OpenTrackTuningPacket]. */
        fun open(pos: BlockPos, values: CompoundTag) {
            Minecraft.getInstance().execute {
                Minecraft.getInstance().setScreen(TrackTuningScreen(pos, values))
            }
        }
    }
}
