package dev.nitka.nodewire.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwApplier
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.render.EmptyRenderer
import dev.nitka.nodewire.ui.render.Renderer
import org.appliedenergistics.yoga.YogaNode

/**
 * Low-level Compose primitive: emits a single [UiNode] into the tree.
 * Higher-level composables ([Box], [Spacer], [Row], [Column], …) all funnel
 * through here — they differ only in the [Renderer] and any default [yogaConfig].
 *
 * The [update] block runs once per recomposition; each `set(value) { ... }`
 * fires the lambda only when its captured value actually changed, so a
 * stable [modifier]/[renderer] doesn't re-bucket on every frame.
 */
@Composable
inline fun Layout(
    modifier: Modifier = Modifier,
    renderer: Renderer = EmptyRenderer,
    noinline yogaConfig: YogaNode.() -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    ComposeNode<UiNode, NwApplier>(
        factory = ::UiNode,
        update = {
            set(modifier) { this.modifier = it }
            set(renderer) { this.renderer = it }
            set(yogaConfig) { yoga.apply(it) }
        },
        content = content,
    )
}
