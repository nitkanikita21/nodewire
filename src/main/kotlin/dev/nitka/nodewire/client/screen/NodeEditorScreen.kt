package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.EvalResult
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.StatefulGraphEvaluator
import dev.nitka.nodewire.graph.StockNodeTypes
import kotlinx.coroutines.delay
import dev.nitka.nodewire.net.NodewireNetwork
import dev.nitka.nodewire.net.SaveGraphPacket
import net.minecraftforge.network.PacketDistributor
import dev.nitka.nodewire.ui.canvas.NodeCanvas
import dev.nitka.nodewire.ui.canvas.rememberCanvasState
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.input.pointerInput
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

    /**
     * Send the (possibly edited) graph back to the server when the screen
     * closes. The packet handler validates everything; rejections show up
     * in the server log but the player sees nothing — the worst case is
     * the BE keeps its previous graph.
     *
     * No dirty-tracking yet — we save on every close. Cost is small (one
     * packet, NBT-sized to the graph) and the server-side validation guards
     * the BE either way.
     */
    override fun removed() {
        NodewireNetwork.CHANNEL.send(
            PacketDistributor.SERVER.noArg(),
            SaveGraphPacket(pos, graph),
        )
        super.removed()
    }

    @Composable
    override fun Content() {
        NwThemeProvider {
            val canvas = rememberCanvasState()
            val editor = remember(graph) { EditorState(graph) }
            val nodes = remember(editor.nodesVersion) { graph.nodes.values.toList() }
            // Live evaluator: runs once per game tick (50ms) so stateful
            // nodes (Timer) advance smoothly in the editor preview. Graph
            // mutations show up on the next tick. External inputs empty
            // for now — block_input outputs default to `false`.
            val evaluator = remember(graph) { StatefulGraphEvaluator(graph) }
            var evalResult by remember { mutableStateOf(EvalResult(emptyMap())) }
            LaunchedEffect(evaluator) {
                while (true) {
                    delay(TICK_INTERVAL_MS)
                    evalResult = evaluator.tick()
                }
            }

            CompositionLocalProvider(
                LocalEditorState provides editor,
                LocalEvalResult provides evalResult,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NwTheme.colors.background)
                        // Catch-all handler for sticky wire drags:
                        //   * Move events follow the cursor so the sticky
                        //     wire trails the mouse with no button held —
                        //     converted from screen to world via the canvas
                        //     state since this Box is outside the pose.
                        //   * An unconsumed Press while sticky drag is
                        //     active means the user clicked off a pin —
                        //     cancel the chain.
                        //   * Non-sticky drags use only press-drag-release
                        //     on the source pin; we never run for them.
                        .pointerInput { ev, _, _ ->
                            when {
                                // Right-click on empty area → open the
                                // "Add Node" context menu. World position
                                // = screen / zoom - pan (canvas origin 0,0).
                                ev is PointerEvent.Press
                                    && ev.button == RIGHT_BUTTON
                                    && editor.wireDragSource == null
                                -> {
                                    val worldX = ev.x.toFloat() / canvas.zoom - canvas.panX
                                    val worldY = ev.y.toFloat() / canvas.zoom - canvas.panY
                                    editor.openCreateMenu(ev.x, ev.y, CanvasPos(worldX, worldY))
                                    true
                                }
                                // Sticky wire drag handling — unchanged.
                                editor.wireDragSource != null && editor.wireDragSticky -> when (ev) {
                                    is PointerEvent.Move -> {
                                        val worldX = ev.x.toFloat() / canvas.zoom - canvas.panX
                                        val worldY = ev.y.toFloat() / canvas.zoom - canvas.panY
                                        editor.setCursor(worldX, worldY)
                                        false
                                    }
                                    is PointerEvent.Press -> {
                                        editor.cancelWireDrag()
                                        true
                                    }
                                    else -> false
                                }
                                else -> false
                            }
                        },
                ) {
                    NodeCanvas(state = canvas, modifier = Modifier.fillMaxSize()) {
                        // Wires render BEFORE cards so they pass under the
                        // node bodies instead of clipping over them.
                        WireLayer()
                        for (node in nodes) {
                            NodeCard(node = node)
                        }
                    }
                    // Context menu — rendered when state is non-null. The
                    // Popup handles overlay layering itself.
                    editor.contextMenu?.let { target ->
                        NodeContextMenu(target = target, editor = editor)
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
    private companion object {
        private const val RIGHT_BUTTON = 1
        /** MC's game tick is 50 ms (20 Hz). Match it so Timer pulses align with the server's tick rate. */
        private const val TICK_INTERVAL_MS = 50L
    }

    private fun seedIfEmpty(g: NodeGraph) {
        if (g.nodes.isNotEmpty()) return
        val bool1 = StockNodeTypes.BOOL_CONST.newInstance(CanvasPos(40f, 40f))
        val int1 = StockNodeTypes.INT_CONST.newInstance(CanvasPos(40f, 140f))
        val sideIn = StockNodeTypes.SIDE_INPUT.newInstance(CanvasPos(40f, 240f))
        val and = StockNodeTypes.AND.newInstance(CanvasPos(260f, 40f))
        val toRs = StockNodeTypes.CONVERT_TO_REDSTONE.newInstance(CanvasPos(260f, 160f))
        val sideOut = StockNodeTypes.SIDE_OUTPUT.newInstance(CanvasPos(500f, 160f))
        g.add(bool1); g.add(int1); g.add(sideIn); g.add(and); g.add(toRs); g.add(sideOut)
        // bool_const → AND.a; int_const → ConvertToRedstone → SideOutput
        g.addEdge(Edge(PinRef(bool1.id, "out"), PinRef(and.id, "a")))
        g.addEdge(Edge(PinRef(int1.id, "out"), PinRef(toRs.id, "in")))
        g.addEdge(Edge(PinRef(toRs.id, "out"), PinRef(sideOut.id, "in")))
    }
}
