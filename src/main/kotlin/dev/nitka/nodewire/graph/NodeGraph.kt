package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * The mutable graph stored by `LogicBlockEntity`. Round-trips losslessly
 * through [toNbt] / [fromNbt].
 *
 * The MVP enforces at-most-one source per input pin via [edges] — that
 * invariant is checked on save by the server-side packet handler, not by
 * this class (kept as a plain container so editor code can mutate freely
 * during a session and validate once at the end).
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

    /** Convenience: clear any existing edge into [to] before adding [edge] (MVP: 1 input = 1 source). */
    fun connectReplacing(edge: Edge) {
        edges.removeAll { it.to == edge.to }
        edges.add(edge)
    }

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        val nodesList = ListTag()
        for (node in nodes.values) nodesList.add(node.toNbt())
        tag.put("nodes", nodesList)
        val edgesList = ListTag()
        for (edge in edges) edgesList.add(edge.toNbt())
        tag.put("edges", edgesList)
        return tag
    }

    companion object {
        fun fromNbt(tag: CompoundTag): NodeGraph {
            val g = NodeGraph()
            val nodesList = tag.getList("nodes", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until nodesList.size) {
                val node = Node.fromNbt(nodesList.getCompound(i))
                g.nodes[node.id] = node
            }
            val edgesList = tag.getList("edges", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until edgesList.size) {
                g.edges.add(Edge.fromNbt(edgesList.getCompound(i)))
            }
            return g
        }
    }
}
