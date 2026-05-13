package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.StockNodeTypes
import dev.nitka.nodewire.ui.canvas.NodeCanvas
import dev.nitka.nodewire.ui.canvas.rememberCanvasState
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Full-screen node editor. Opens when the player right-clicks a
 * `logic_block` in the world. Reads the BE's [NodeGraph] (already synced
 * client-side via `LogicBlockEntity.getUpdateTag`) and renders every node
 * inside a [NodeCanvas] — middle-drag to pan, Ctrl+wheel to zoom.
 *
 * Phase-5/9 progressive build:
 *   * No editing controls yet (no add / delete / drag-to-move / wires).
 *   * On open, if the graph is empty we seed it with one sample of each
 *     constant type so an empty-block right-click shows something useful.
 *   * No save-on-close yet — Phase 7 wires `SaveGraphPacket` into [removed].
 */
class NodeEditorScreen(val pos: BlockPos, initialGraph: NodeGraph) :
    NwComposeScreen(Component.literal("Node Editor @ ${pos.toShortString()}")) {

    private val graph: NodeGraph = initialGraph.also { seedIfEmpty(it) }

    @Composable
    override fun Content() {
        NwThemeProvider {
            val canvas = rememberCanvasState()
            // The `nodes` list reference is what drives recomposition when
            // we (eventually) add/remove nodes. Snapshot the values once per
            // composition — the wrapper state will be replaced wholesale by
            // future edit actions, triggering recomposition for free.
            var nodes by remember { mutableStateOf(graph.nodes.values.toList()) }
            // Future hook: rebind whenever an edit lands. Kept as a no-op
            // reference here so the compiler doesn't strip the setter and
            // the editing slice can wire it without touching this file.
            @Suppress("UNUSED_VARIABLE") val setNodes: (List<Node>) -> Unit = { nodes = it }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NwTheme.colors.background),
            ) {
                NodeCanvas(state = canvas, modifier = Modifier.fillMaxSize()) {
                    for (node in nodes) {
                        NodeCard(node = node)
                    }
                }
            }
        }
    }

    /**
     * Seed an empty BE with a small starter set of nodes so the editor
     * never opens to a blank canvas. Skipped if the graph already has any
     * content — the user's own work is never overwritten.
     */
    private fun seedIfEmpty(g: NodeGraph) {
        if (g.nodes.isNotEmpty()) return
        g.add(StockNodeTypes.BOOL_CONST.newInstance(CanvasPos(40f, 40f)))
        g.add(StockNodeTypes.INT_CONST.newInstance(CanvasPos(40f, 130f)))
        g.add(StockNodeTypes.AND.newInstance(CanvasPos(220f, 40f)))
        g.add(StockNodeTypes.ADD_INT.newInstance(CanvasPos(220f, 130f)))
        g.add(StockNodeTypes.BLOCK_OUTPUT.newInstance(CanvasPos(400f, 40f)))
    }
}
