package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxHeight
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.Color
import net.minecraft.network.chat.Component

/**
 * Phase 7 demo: Row with three regions — fixed red square on the left,
 * a Column of three evenly-spaced small squares in the middle, blue panel
 * grabs all remaining width via [weight]. SpaceBetween puts gaps between
 * regions; padding adds breathing room from the screen edge.
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16)
                .background(Color(0xFF_22_22_28.toInt())),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(30).background(Color(0xFF_E8_5C_5C.toInt())))
            Column(verticalArrangement = Arrangement.SpaceEvenly) {
                Box(Modifier.size(20).background(Color(0xFF_5C_C8_E8.toInt())))
                Box(Modifier.size(20).background(Color(0xFF_E8_C8_5C.toInt())))
                Box(Modifier.size(20).background(Color(0xFF_7C_E8_5C.toInt())))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF_4A_9E_FF.toInt()))
            )
        }
    }
}
