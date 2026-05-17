package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser

/**
 * Serialize / parse a subgraph through the system clipboard.
 *
 * Format: a [CompoundTag] with `nodewire_subgraph: 1b` + `version: 1` +
 * `graph: <NodeGraph.CODEC payload>`. Stringified to SNBT for transport.
 * On decode, missing marker → null (foreign clipboard text is silently
 * ignored; Ctrl+V never disturbs other applications' clipboard).
 */
object GraphClipboard {

    private const val MARKER = "nodewire_subgraph"
    private const val VERSION_KEY = "version"
    private const val GRAPH_KEY = "graph"
    private const val CURRENT_VERSION = 1

    fun encode(graph: NodeGraph): String {
        val payload = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph)
            .result().orElseThrow()
        val wrapper = CompoundTag().apply {
            putBoolean(MARKER, true)
            putInt(VERSION_KEY, CURRENT_VERSION)
            put(GRAPH_KEY, payload)
        }
        return wrapper.toString()
    }

    fun decode(raw: String): NodeGraph? {
        val parsed = runCatching { TagParser.parseTag(raw) }.getOrNull() ?: return null
        val wrapper = parsed as? CompoundTag ?: return null
        if (!wrapper.getBoolean(MARKER)) return null
        val graphTag = wrapper.get(GRAPH_KEY) ?: return null
        return NodeGraph.CODEC.parse(NbtOps.INSTANCE, graphTag).result().orElse(null)
    }
}
