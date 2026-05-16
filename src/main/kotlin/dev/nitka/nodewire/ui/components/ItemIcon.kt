package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.render.ItemIconRenderer
import net.minecraft.world.item.ItemStack

/**
 * Render a vanilla item icon. Default size is 16×16 — pass [modifier] with
 * a different `size(...)` for non-standard sizes (won't scale, just clips).
 * Empty stacks render nothing.
 */
@Composable
fun ItemIcon(
    stack: ItemStack,
    modifier: Modifier = Modifier.size(16),
) {
    Layout(
        modifier = modifier,
        renderer = ItemIconRenderer(stack),
    )
}
