package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.Color
import net.minecraft.network.chat.Component

/**
 * Phase 6 demo: a red 100×50 [Box] containing a green 40×20 [Box].
 * Confirms Compose composition → NwApplier → Yoga → SurfaceRenderer → NwCanvas.
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        Box(Modifier.size(100, 50).background(Color(0xFF_FF_00_00.toInt()))) {
            Box(Modifier.size(40, 20).background(Color(0xFF_00_FF_00.toInt())))
        }
    }
}
