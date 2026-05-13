package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.components.Text
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
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.network.chat.Component

/**
 * Phase 9 demo: typography ramp (title / subtitle / body / caption) inside
 * a themed Row, plus the Phase 7 three-region layout. Confirms TextRenderer
 * end-to-end including non-1 scale via gfx.pose().
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        NwThemeProvider {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NwTheme.dimens.space16)
                    .background(NwTheme.colors.background),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30).background(NwTheme.colors.danger))
                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                    Text("Title", style = NwTheme.typography.title)
                    Text("Subtitle", style = NwTheme.typography.subtitle)
                    Text(
                        "Body — quick brown fox",
                        style = NwTheme.typography.body.copy(color = NwTheme.colors.onSurfaceMuted),
                    )
                    Text(
                        "caption ✦ secondary line",
                        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceDisabled),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(NwTheme.colors.accent),
                )
            }
        }
    }
}
