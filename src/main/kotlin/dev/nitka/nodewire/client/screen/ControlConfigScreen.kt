package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.control.BindKind
import dev.nitka.nodewire.block.control.Binding
import dev.nitka.nodewire.net.SetControlConfigPacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.scroll.rememberScrollState
import dev.nitka.nodewire.ui.scroll.verticalScroll
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * Binding editor for a Control Block (Phase 3). Add/remove bindings, cycle each
 * one's kind (BUTTON / AXIS / VECTOR / MOUSE_LOOK / SCROLL), name its output pin
 * and assign keys by pressing them. Commits the whole layout on close via
 * [SetControlConfigPacket]; the BE re-derives its pin set.
 */
class ControlConfigScreen(
    private val pos: BlockPos,
    initial: List<Binding>,
    /** `""` = Control Block; else a panel `joystick_ctrl` element's base pin id. */
    private val element: String = "",
) : NwComposeScreen(Component.literal("Control Block")) {

    private var bindings: List<Binding> by mutableStateOf(initial.map { it.copy() })

    /** (rowIndex, keySlot) awaiting a key/button press, or null. */
    private var capturing: Pair<Int, Int>? by mutableStateOf(null)

    // ── raw input capture (intercepts before Compose while a slot is armed) ──

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val cap = capturing
        if (cap != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) assignKey(cap.first, cap.second, keyCode)
            capturing = null
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(x: Double, y: Double, btn: Int): Boolean {
        val cap = capturing
        if (cap != null) {
            assignKey(cap.first, cap.second, Binding.MOUSE_BUTTON_BASE + btn)
            capturing = null
            return true
        }
        return super.mouseClicked(x, y, btn)
    }

    override fun onClose() {
        PacketDistributor.sendToServer(
            SetControlConfigPacket(pos, bindings.filter { it.pin.isNotBlank() }, element),
        )
        super.onClose()
    }

    // ── mutators ─────────────────────────────────────────────────────────

    private fun update(i: Int, b: Binding) {
        bindings = bindings.mapIndexed { idx, x -> if (idx == i) b else x }
    }

    private fun assignKey(row: Int, slot: Int, code: Int) {
        val b = bindings.getOrNull(row) ?: return
        val keys = b.keys.toMutableList()
        while (keys.size <= slot) keys.add(-1)
        keys[slot] = code
        update(row, b.copy(keys = keys))
    }

    private fun cycleKind(i: Int) {
        val b = bindings[i]
        val next = BindKind.entries[(b.kind.ordinal + 1) % BindKind.entries.size]
        val keys = b.keys.toMutableList()
        while (keys.size < next.keyCount) keys.add(-1)
        while (keys.size > next.keyCount) keys.removeAt(keys.size - 1)
        update(i, b.copy(kind = next, keys = keys))
    }

    private fun addBinding() {
        bindings = bindings + Binding("pin${bindings.size + 1}", BindKind.BUTTON, listOf(-1))
    }

    private fun removeBinding(i: Int) {
        bindings = bindings.filterIndexed { idx, _ -> idx != i }
        capturing = null
    }

    private fun keyLabel(code: Int): String = dev.nitka.nodewire.block.control.KeyNames.label(code)

    // ── UI ───────────────────────────────────────────────────────────────

    @Composable
    override fun Content() {
        NwThemeProvider {
            val list = bindings
            Box(modifier = Modifier.fillMaxSize().padding(NwTheme.dimens.space8)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    style = SurfaceStyle(
                        color = NwTheme.colors.surface,
                        shape = NwTheme.shapes.medium,
                        border = BorderStroke(1, NwTheme.colors.border),
                        padding = PaddingValues(NwTheme.dimens.space8),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Center,
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Text("Control Block — bindings", style = NwTheme.typography.subtitle)
                            Box(modifier = Modifier.weight(1f)) {}
                            Button(onClick = { addBinding() }, style = ButtonDefaults.ghost()) { Text("+ Add") }
                            Button(onClick = { onClose() }) { Text("Close") }
                        }

                        val scroll = rememberScrollState()
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            if (list.isEmpty()) {
                                Text(
                                    "(no bindings — click + Add)",
                                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                                )
                            }
                            list.forEachIndexed { i, b -> BindingRow(i, b) }
                        }

                        capturing?.let {
                            Text(
                                "Press a key or mouse button to bind…  (Esc to cancel)",
                                style = NwTheme.typography.caption.copy(color = NwTheme.colors.accent),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BindingRow(i: Int, b: Binding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            TextInput(
                value = b.pin,
                onValueChange = { update(i, b.copy(pin = it)) },
                modifier = Modifier.width(96),
                placeholder = "pin",
            )
            Button(onClick = { cycleKind(i) }, style = ButtonDefaults.ghost()) {
                Text(b.kind.name.lowercase())
            }
            for (slot in 0 until b.kind.keyCount) {
                val armed = capturing == (i to slot)
                Button(onClick = { capturing = i to slot }, style = ButtonDefaults.ghost()) {
                    Text(if (armed) "…" else keyLabel(b.keys.getOrElse(slot) { -1 }))
                }
            }
            Box(modifier = Modifier.weight(1f)) {}
            Button(onClick = { removeBinding(i) }, style = ButtonDefaults.ghost()) { Text("✕") }
        }
    }

    companion object {
        /** Open the editor for the control block/panel element at [pos] (client). */
        fun open(pos: BlockPos, bindings: List<Binding>, element: String = "") {
            Minecraft.getInstance().setScreen(ControlConfigScreen(pos, bindings, element))
        }
    }
}
