package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Divider
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.network.chat.Component

/**
 * Phase 11 demo: four [Button] variants with a click counter at the top.
 * Each button increments [clicks]; the Text above reads the counter.
 * Demonstrates Surface/Button/Divider + per-state styling + content-color
 * cascade (Text inside Button picks up `LocalContentColor`).
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        NwThemeProvider {
            var clicks by remember { mutableStateOf(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NwTheme.dimens.space16)
                    .background(NwTheme.colors.background),
                verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space12),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("Buttons", style = NwTheme.typography.title)
                Text(
                    "Clicked $clicks times",
                    style = NwTheme.typography.body.copy(color = NwTheme.colors.onSurfaceMuted),
                )
                Divider()
                Row(horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8)) {
                    Button(onClick = { clicks++ }) {
                        Text("Filled")
                    }
                    Button(onClick = { clicks++ }, style = ButtonDefaults.outlined()) {
                        Text("Outlined")
                    }
                    Button(onClick = { clicks++ }, style = ButtonDefaults.ghost()) {
                        Text("Ghost")
                    }
                    Button(onClick = { clicks++ }, style = ButtonDefaults.danger()) {
                        Text("Danger")
                    }
                    Button(onClick = { clicks++ }, enabled = false) {
                        Text("Disabled")
                    }
                }
            }
        }
    }
}
