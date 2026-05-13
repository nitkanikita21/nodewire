package dev.nitka.nodewire.ui.layout

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.render.SurfaceRenderer

/**
 * A rectangular container with an optional background and children.
 * Children stack on top of each other (Phase 6 — alignment lands in Phase 7
 * with `contentAlignment`).
 */
@Composable
fun Box(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Layout(modifier = modifier, renderer = SurfaceRenderer, content = content)
}
