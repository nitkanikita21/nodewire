package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.NwComposeScreen
import net.minecraft.network.chat.Component

/**
 * Phase 5: empty content — verifies the screen opens, recomposition kicks
 * off, and disposal happens cleanly. Visual content arrives once Phase 6 adds
 * the Layout primitive + Box/Spacer composables.
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        // Intentionally empty for Phase 5 — see KDoc above.
    }
}
