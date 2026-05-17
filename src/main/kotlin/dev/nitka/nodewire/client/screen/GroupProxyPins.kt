package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.graph.PinType

/**
 * One auto-derived pin on a collapsed group's tile. The proxy is purely
 * a render-time artefact — it carries enough info for the renderer to
 * draw the row, and for `WireLayer` to detour wires that touch
 * [innerNode] through this pin's position.
 */
data class GroupProxyPin(
    val side: PinSide,
    val innerNode: NodeId,
    val innerPin: String,
    val type: PinType,
    val label: String,
)

object GroupProxyPins {

    /**
     * Compute the proxy pin set for a group's collapsed tile. Caller
     * supplies the recursive member-node closure so we don't have to
     * traverse `MemberRef.Sub` here.
     *
     * Label resolution order:
     * 1. Pin label override from `group.pinLabelOverrides["${nodeId}.${pinId}"]`
     * 2. Edge label (first edge's label for this pin, if non-null/non-empty)
     * 3. Pin name
     *
     * Duplicates within the same side are disambiguated:
     * - Unique (side, label) → kept as-is.
     * - Colliding → replaced with `"${typeDisplayName}.${pinName}"`.
     * - Still colliding → numeric suffix " 2", " 3", … in encounter order.
     */
    fun compute(graph: NodeGraph, group: Group, memberClosure: Set<NodeId>): List<GroupProxyPin> {
        // Pass 1: collect one candidate per unique (node, pin, side).
        data class Candidate(
            val side: PinSide,
            val innerNode: NodeId,
            val innerPin: String,
            val type: PinType,
            val pinName: String,
            val typeDisplayName: String,
            val edgeLabel: String?,
        )

        val candidates = mutableListOf<Candidate>()
        val seen = HashSet<Triple<NodeId, String, PinSide>>()

        for (e in graph.edges) {
            val fromInside = e.from.node in memberClosure
            val toInside = e.to.node in memberClosure
            if (fromInside == toInside) continue

            val side = if (fromInside) PinSide.Output else PinSide.Input
            val node = if (fromInside) graph.nodes[e.from.node] else graph.nodes[e.to.node]
            if (node == null) continue
            val pinId = if (fromInside) e.from.pin else e.to.pin
            val pin = if (fromInside) node.outputs.firstOrNull { it.id == pinId }
                      else node.inputs.firstOrNull { it.id == pinId }
            if (pin == null) continue

            val key = Triple(node.id, pin.id, side)
            if (!seen.add(key)) continue

            val typeDn = NodeTypeRegistry.get(node.typeKey)?.displayName ?: node.typeKey.path
            candidates.add(Candidate(side, node.id, pin.id, pin.type, pin.name, typeDn, e.label))
        }

        // Pass 2: pick base label for each candidate.
        val baseLabels = candidates.map { c ->
            group.pinLabelOverrides["${c.innerNode}.${c.innerPin}"]
                ?: c.edgeLabel?.takeIf { it.isNotEmpty() }
                ?: c.pinName
        }

        // Pass 3: disambiguate — first round, replace collisions with typeDisplayName.pinName.
        val finalLabels = MutableList(candidates.size) { baseLabels[it] }

        val sideLabelToIdxs = HashMap<Pair<PinSide, String>, MutableList<Int>>()
        for ((i, lbl) in finalLabels.withIndex()) {
            sideLabelToIdxs.getOrPut(candidates[i].side to lbl) { mutableListOf() }.add(i)
        }
        for ((_, idxs) in sideLabelToIdxs) {
            if (idxs.size <= 1) continue
            for (i in idxs) {
                val c = candidates[i]
                finalLabels[i] = "${c.typeDisplayName}.${c.pinName}"
            }
        }

        // Pass 4: second round — numeric suffix for any remaining collisions.
        val secondBuckets = HashMap<Pair<PinSide, String>, MutableList<Int>>()
        for ((i, lbl) in finalLabels.withIndex()) {
            secondBuckets.getOrPut(candidates[i].side to lbl) { mutableListOf() }.add(i)
        }
        for ((_, idxs) in secondBuckets) {
            if (idxs.size <= 1) continue
            for ((order, i) in idxs.withIndex()) {
                if (order == 0) continue
                finalLabels[i] = "${finalLabels[i]} ${order + 1}"
            }
        }

        return candidates.mapIndexed { i, c ->
            GroupProxyPin(c.side, c.innerNode, c.innerPin, c.type, finalLabels[i])
        }
    }

    /**
     * Recursively expand `group.members` into the flat set of node ids.
     * Sub-group entries are resolved by id lookup in [allGroups].
     */
    fun memberClosure(group: Group, allGroups: Map<GroupId, Group>): Set<NodeId> {
        val out = HashSet<NodeId>()
        val stack = ArrayDeque<Group>()
        stack.addLast(group)
        val seen = HashSet<GroupId>()
        while (stack.isNotEmpty()) {
            val g = stack.removeLast()
            if (!seen.add(g.id)) continue
            for (m in g.members) {
                when (m) {
                    is MemberRef.Node -> out.add(m.id)
                    is MemberRef.Sub -> allGroups[m.id]?.let(stack::addLast)
                }
            }
        }
        return out
    }
}
