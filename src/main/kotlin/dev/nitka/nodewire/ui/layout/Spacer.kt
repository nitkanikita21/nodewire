package dev.nitka.nodewire.ui.layout

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.render.EmptyRenderer

/**
 * Empty node that takes up space according to its [modifier] (typically
 * [size]/[width]/[height]). Useful for fixed gaps in Row/Column or to fill
 * remaining space with `Modifier.weight(1f)` once that exists.
 */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Layout(modifier = modifier, renderer = EmptyRenderer)
}
