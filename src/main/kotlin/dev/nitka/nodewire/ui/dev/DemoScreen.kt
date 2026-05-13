package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.clickable
import dev.nitka.nodewire.ui.modifier.input.onHover
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
 * Phase 10 demo: same typography stack but with an interactive counter box
 * on the left (clickable + onHover) and a draggable square at the bottom
 * (pointerInput would be Phase-10.5+, kept simple for now). Confirms
 * pointer-input pipeline end-to-end: hit test → handler → state mutation
 * → recomposition → repaint.
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
                CounterBox()
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

    /**
     * 80×80 panel that increments a count on click and lightens on hover.
     * Inner Column centers the label and value vertically; the label uses
     * `caption` to stay subdued and the value uses `title` for the focal
     * count. Demonstrates state + recomposition + click + hover end-to-end.
     */
    @Composable
    private fun CounterBox() {
        var count by remember { mutableStateOf(0) }
        var hovered by remember { mutableStateOf(false) }
        val bg = if (hovered) NwTheme.colors.accentHover else NwTheme.colors.accent
        Box(
            modifier = Modifier
                .size(80)
                .background(bg)
                .onHover { hovered = it }
                .clickable { count++ },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(NwTheme.dimens.space8),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Center,
            ) {
                Text("CLICKS", style = NwTheme.typography.caption.copy(color = NwTheme.colors.onAccent))
                Text(
                    "$count",
                    style = NwTheme.typography.title.copy(color = NwTheme.colors.onAccent),
                )
            }
        }
    }
}
