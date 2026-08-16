package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.net.SetCameraEyePacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
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
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Remote Camera eye tuning — one row per parameter, each a SLIDER plus a
 * numeric TEXT FIELD (either edits the same value): the facing-relative
 * viewpoint displacement (right / up / forward, ±16 blocks) and the aim
 * offsets (yaw ±180°, pitch ±90°) on top of the block facing.
 *
 * Every change streams a [SetCameraEyePacket] immediately, so a screen fed by
 * this camera live-previews while you drag. Reset clears the eye back to the
 * block itself.
 */
class RemoteCameraScreen(
    private val pos: BlockPos,
    r: Double,
    u: Double,
    f: Double,
    yaw: Float,
    pitch: Float,
) : NwComposeScreen(Component.literal("Remote Camera")) {

    private data class Spec(val key: String, val label: String, val min: Double, val max: Double, val step: Double)

    private val specs = listOf(
        Spec("right", "Right", -CameraBlockEntity.MAX_EYE_OFFSET, CameraBlockEntity.MAX_EYE_OFFSET, 0.05),
        Spec("up", "Up", -CameraBlockEntity.MAX_EYE_OFFSET, CameraBlockEntity.MAX_EYE_OFFSET, 0.05),
        Spec("forward", "Forward", -CameraBlockEntity.MAX_EYE_OFFSET, CameraBlockEntity.MAX_EYE_OFFSET, 0.05),
        Spec("yaw", "Yaw", -180.0, 180.0, 0.5),
        Spec("pitch", "Pitch", -90.0, 90.0, 0.5),
    )

    private var values: Map<String, Double> by mutableStateOf(
        mapOf("right" to r, "up" to u, "forward" to f, "yaw" to yaw.toDouble(), "pitch" to pitch.toDouble()),
    )

    /** Text being edited per field (kept separate so half-typed numbers like
     *  "-" or "3." don't get clobbered by reformatting). */
    private var edits: Map<String, String> by mutableStateOf(emptyMap())

    private fun set(key: String, v: Double) {
        val s = specs.first { it.key == key }
        values = values + (key to v.coerceIn(s.min, s.max))
        edits = edits - key
        send()
    }

    private fun send(clear: Boolean = false) {
        PacketDistributor.sendToServer(
            SetCameraEyePacket(
                pos,
                values.getValue("right"), values.getValue("up"), values.getValue("forward"),
                values.getValue("yaw").toFloat(), values.getValue("pitch").toFloat(),
                clear,
            ),
        )
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
                        Text("Remote Camera — eye", style = NwTheme.typography.subtitle)
                        Text(
                            "Viewpoint offset (blocks, relative to the block's facing) + aim.",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                        specs.forEach { s -> ParamRow(s) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Button(onClick = {
                                values = mapOf("right" to 0.0, "up" to 0.0, "forward" to 0.0, "yaw" to 0.0, "pitch" to 0.0)
                                edits = emptyMap()
                                send(clear = true)
                            }) { Text("Reset") }
                            Box(modifier = Modifier.weight(1f)) {}
                            Button(onClick = { onClose() }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ParamRow(s: Spec) {
        val v = values.getValue(s.key)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(s.label, style = NwTheme.typography.body, modifier = Modifier.width(LABEL_W))
            SliderTrack(s, v)
            TextInput(
                value = edits[s.key] ?: FMT.format(v),
                onValueChange = { text ->
                    edits = edits + (s.key to text)
                    text.toDoubleOrNull()?.let { parsed ->
                        val clamped = parsed.coerceIn(s.min, s.max)
                        values = values + (s.key to clamped)
                        send()
                    }
                },
                modifier = Modifier.width(FIELD_W),
            )
        }
    }

    @Composable
    private fun SliderTrack(s: Spec, v: Double) {
        val frac = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
        val fillW = (frac * TRACK_W).toInt()
        fun setFromX(lx: Int) {
            val fr = (lx.toDouble() / TRACK_W).coerceIn(0.0, 1.0)
            val raw = s.min + fr * (s.max - s.min)
            val snapped = Math.round(raw / s.step) * s.step
            set(s.key, snapped)
        }
        Box(
            modifier = Modifier.width(TRACK_W).height(TRACK_H)
                .background(NwTheme.colors.surfaceHover, NwTheme.shapes.small)
                .pointerInput { ev, lx, _ ->
                    when (ev) {
                        is PointerEvent.Press -> if (ev.button == 0) { setFromX(lx); true } else false
                        is PointerEvent.Drag -> if (ev.button == 0) { setFromX(lx); true } else false
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

    companion object {
        private const val PANEL_W = 320
        private const val LABEL_W = 52
        private const val FIELD_W = 56
        private const val TRACK_W = 160
        private const val TRACK_H = 12
        private val FMT = java.text.DecimalFormat("0.##")

        fun open(pos: BlockPos, r: Double, u: Double, f: Double, yaw: Float, pitch: Float) {
            Minecraft.getInstance().setScreen(RemoteCameraScreen(pos, r, u, f, yaw, pitch))
        }
    }
}
