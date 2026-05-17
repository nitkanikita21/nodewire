package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The mutable graph stored by `LogicBlockEntity`. Round-trips losslessly
 * through [CODEC].
 *
 * Nodes are stored as a flat list in the codec (each node carries its
 * own id); the in-memory representation is a map for O(1) lookup. The
 * codec rebuilds the map on parse.
 */
class NodeGraph {
    val nodes: MutableMap<NodeId, Node> = mutableMapOf()
    val edges: MutableList<Edge> = mutableListOf()

    fun add(node: Node) { nodes[node.id] = node }

    fun removeNode(id: NodeId) {
        nodes.remove(id) ?: return
        edges.removeAll { it.from.node == id || it.to.node == id }
    }

    fun addEdge(edge: Edge) { edges.add(edge) }

    fun removeEdge(edge: Edge) { edges.remove(edge) }

    /** Convenience: clear any existing edge into [to] before adding [edge]. */
    fun connectReplacing(edge: Edge) {
        edges.removeAll { it.to == edge.to }
        edges.add(edge)
    }

    /**
     * Full deep copy via codec round-trip. Cost is a few hundred μs per
     * snapshot at typical sizes; the codec already handles every node
     * config / edge shape correctly, so this is robust against future
     * Node/Edge schema additions.
     */
    fun deepCopy(): NodeGraph {
        val tag = CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, this)
            .result().orElseThrow()
        return CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
            .result().orElseThrow()
    }

    companion object {
        val CODEC: Codec<NodeGraph> = RecordCodecBuilder.create { i ->
            i.group(
                Node.CODEC.listOf().fieldOf("nodes").forGetter { g -> g.nodes.values.toList() },
                Edge.CODEC.listOf().fieldOf("edges").forGetter { g -> g.edges.toList() },
            ).apply(i) { nodeList, edgeList ->
                NodeGraph().also { g ->
                    for (n in nodeList) g.nodes[n.id] = n
                    g.edges.addAll(edgeList)
                }
            }
        }
    }
}
