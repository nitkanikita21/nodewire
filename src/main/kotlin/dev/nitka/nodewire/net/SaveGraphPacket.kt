package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.PinRef
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Block
import net.minecraftforge.network.NetworkEvent
import org.slf4j.Logger
import java.util.function.Supplier

/**
 * Client → server: persist the user's edited graph for the [LogicBlockEntity]
 * at [pos]. Server-side handler validates everything before writing:
 *
 *   1. Sender must be within 8 blocks of [pos] (anti-cheat).
 *   2. A [LogicBlockEntity] must exist at [pos].
 *   3. Every edge must reference real pins on real nodes.
 *   4. Edge endpoint types must match (`from.type == to.type`).
 *   5. No two edges may share the same input (`Edge.to`).
 *   6. The graph must be acyclic.
 *
 * Failing any check drops the packet with a warning log — no exception
 * leaks back to the client, and the BE keeps its previous graph.
 */
class SaveGraphPacket(val pos: BlockPos, val graph: NodeGraph) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(CODEC, this)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val center = pos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_DISTANCE_SQR) {
                LOG.warn("Rejecting SaveGraphPacket: {} too far from {}", player.gameProfile.name, pos)
                return@enqueueWork
            }
            val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: run {
                LOG.warn("Rejecting SaveGraphPacket: no LogicBlockEntity at {}", pos)
                return@enqueueWork
            }
            // Codec already parsed the graph during decode.
            val reason = validate(graph)
            if (reason != null) {
                LOG.warn("Rejecting SaveGraphPacket from {}: {}", player.gameProfile.name, reason)
                return@enqueueWork
            }
            be.graph = graph
            // New graph means the cached server-side evaluator references
            // stale node references — rebuild on next tick.
            be.invalidateEvaluator()
            be.setChanged()
            level.sendBlockUpdated(pos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
        }
        c.packetHandled = true
        return true
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()

        // 8 blocks (squared) — same reach Minecraft uses for interactions.
        private const val MAX_DISTANCE_SQR = 8.0 * 8.0

        val CODEC: Codec<SaveGraphPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(SaveGraphPacket::pos),
                NodeGraph.CODEC.fieldOf("graph").forGetter(SaveGraphPacket::graph),
            ).apply(i, ::SaveGraphPacket)
        }

        fun decode(buf: FriendlyByteBuf): SaveGraphPacket = buf.readCodec(CODEC)

        /**
         * Returns `null` if the graph is valid, otherwise a short reason
         * string for logging. Public so tests can pin down the rules.
         */
        fun validate(graph: NodeGraph): String? {
            // Channel name uniqueness — duplicate names within input or
            // output channels would make link-tool resolution ambiguous.
            val seenChannelInNames = mutableSetOf<String>()
            val seenChannelOutNames = mutableSetOf<String>()
            for (node in graph.nodes.values) {
                val name = node.config.getString("name").takeIf { it.isNotEmpty() } ?: continue
                when (node.typeKey.path) {
                    "channel_input" -> if (!seenChannelInNames.add(name)) {
                        return "duplicate channel input name: '$name'"
                    }
                    "channel_output" -> if (!seenChannelOutNames.add(name)) {
                        return "duplicate channel output name: '$name'"
                    }
                }
            }

            val seenInputs = mutableSetOf<PinRef>()
            for (edge in graph.edges) {
                val fromNode = graph.nodes[edge.from.node]
                    ?: return "edge.from.node not in graph: ${edge.from.node}"
                val toNode = graph.nodes[edge.to.node]
                    ?: return "edge.to.node not in graph: ${edge.to.node}"
                val fromPin = fromNode.outputs.firstOrNull { it.id == edge.from.pin }
                    ?: return "no output pin ${edge.from.pin} on ${edge.from.node}"
                val toPin = toNode.inputs.firstOrNull { it.id == edge.to.pin }
                    ?: return "no input pin ${edge.to.pin} on ${edge.to.node}"
                if (fromPin.type != toPin.type) {
                    return "type mismatch: ${fromPin.type} → ${toPin.type}"
                }
                if (!seenInputs.add(edge.to)) {
                    return "duplicate edges into input ${edge.to}"
                }
            }
            if (hasCycle(graph)) return "graph contains a cycle"
            return null
        }

        /**
         * Detects "bad" cycles — those that don't pass through any
         * state-bearing node. A cycle that goes through a Counter/Toggle/
         * Delay etc. is fine: the state node reads its inputs from the
         * previous tick, so the cycle is temporal, not algebraic. Cycles
         * among stateless nodes are real logical errors and rejected.
         *
         * Implementation: build the forward adjacency but drop edges
         * landing on a state node (mirrors what [StatefulGraphEvaluator]
         * does at runtime). If a cycle remains in the reduced graph,
         * reject.
         */
        private fun hasCycle(graph: NodeGraph): Boolean {
            fun isStateNode(id: NodeId): Boolean {
                val node = graph.nodes[id] ?: return false
                val type = dev.nitka.nodewire.graph.NodeTypeRegistry.get(node.typeKey) ?: return false
                return type.tickEvaluator != null
            }

            val adj: Map<NodeId, List<NodeId>> = graph.edges
                .filterNot { isStateNode(it.to.node) }
                .groupBy { it.from.node }
                .mapValues { (_, edges) -> edges.map { it.to.node } }

            val visited = mutableSetOf<NodeId>()
            val onStack = mutableSetOf<NodeId>()

            fun dfs(n: NodeId): Boolean {
                if (n in onStack) return true
                if (n in visited) return false
                visited.add(n); onStack.add(n)
                for (next in adj[n].orEmpty()) {
                    if (dfs(next)) return true
                }
                onStack.remove(n)
                return false
            }

            return graph.nodes.keys.any { it !in visited && dfs(it) }
        }
    }
}
