package dev.nitka.nodewire.graph

/**
 * Stateless logic for instantiating and re-syncing template-linked groups.
 *
 * Two entry points:
 *   - [instantiate] — create a fresh instance in [host], allocating new
 *     runtime [NodeId]s for every template node and recording the mapping
 *     on the new [Group]. Translates internal edges; nested template
 *     groups recurse with cycle protection.
 *   - [applyTemplateChange] — diff the existing instance's idMap against
 *     a new [GroupTemplate]. Preserves runtime ids for unchanged template
 *     ids; adds new ones; removes vanished ones, returning the dropped
 *     external edges so the editor can toast them.
 */
object GroupTemplateResolver {

    data class ResolveResult(val groupId: GroupId, val droppedEdges: List<Edge>)

    fun instantiate(
        host: NodeGraph,
        template: GroupTemplate,
        templateFile: String?,
        anchor: CanvasPos,
        seenFiles: Set<String> = emptySet(),
        resolve: (String) -> GroupTemplate?,
    ): ResolveResult {
        if (templateFile != null && templateFile in seenFiles) {
            return ResolveResult(Group.newId(), emptyList())
        }

        val idMap = HashMap<TemplateNodeId, NodeId>()
        for ((tid, tNode) in template.nodes) {
            val rid = Node.newId()
            idMap[tid] = rid
            host.nodes[rid] = tNode.copy(
                id = rid,
                pos = CanvasPos(tNode.pos.x + anchor.x, tNode.pos.y + anchor.y),
            )
        }
        for (e in template.edges) {
            val from = idMap[e.from.node] ?: continue
            val to = idMap[e.to.node] ?: continue
            host.edges.add(Edge(PinRef(from, e.from.pin), PinRef(to, e.to.pin)))
        }
        val subSeen = if (templateFile != null) seenFiles + templateFile else seenFiles
        val members = mutableListOf<MemberRef>()
        for ((tid, _) in template.nodes) members.add(MemberRef.Node(idMap[tid]!!))
        for (subGroup in template.groups) {
            val subFile = subGroup.templateFile
            val subTpl = if (subFile != null) resolve(subFile) else null
            if (subFile != null && subTpl != null) {
                val sub = instantiate(host, subTpl, subFile, anchor, subSeen, resolve)
                members.add(MemberRef.Sub(sub.groupId))
            } else {
                val rewrittenMembers = subGroup.members.mapNotNull { m ->
                    when (m) {
                        is MemberRef.Node -> idMap[m.id]?.let { MemberRef.Node(it) }
                        is MemberRef.Sub -> null
                    }
                }
                val newId = Group.newId()
                host.groups.add(
                    subGroup.copy(
                        id = newId,
                        members = rewrittenMembers,
                        templateIdMap = null,
                        pos = CanvasPos(subGroup.pos.x + anchor.x, subGroup.pos.y + anchor.y),
                    )
                )
                members.add(MemberRef.Sub(newId))
            }
        }
        val gid = Group.newId()
        host.groups.add(
            Group(
                id = gid,
                name = templateFile ?: "Group",
                members = members,
                templateFile = templateFile,
                templateIdMap = if (templateFile != null) idMap else null,
                collapsed = false,
                pos = anchor,
                collapsedSize = null,
            )
        )
        return ResolveResult(gid, emptyList())
    }

    fun applyTemplateChange(
        host: NodeGraph,
        groupId: GroupId,
        newTemplate: GroupTemplate,
        resolve: (String) -> GroupTemplate?,
    ): ResolveResult {
        val idx = host.groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return ResolveResult(groupId, emptyList())
        val cur = host.groups[idx]
        val oldMap = cur.templateIdMap ?: return ResolveResult(groupId, emptyList())

        val newMap = HashMap<TemplateNodeId, NodeId>()

        for ((tid, rid) in oldMap) {
            if (tid in newTemplate.nodes) {
                newMap[tid] = rid
                val tNode = newTemplate.nodes[tid]!!
                val keptPos = host.nodes[rid]?.pos ?: tNode.pos
                host.nodes[rid] = tNode.copy(id = rid, pos = keptPos)
            }
        }
        for ((tid, tNode) in newTemplate.nodes) {
            if (tid in newMap) continue
            val rid = Node.newId()
            newMap[tid] = rid
            host.nodes[rid] = tNode.copy(
                id = rid,
                pos = CanvasPos(tNode.pos.x + cur.pos.x, tNode.pos.y + cur.pos.y),
            )
        }
        val droppedEdges = mutableListOf<Edge>()
        for ((tid, rid) in oldMap) {
            if (tid !in newMap) {
                host.nodes.remove(rid)
                val (touching, kept) = host.edges.partition { it.from.node == rid || it.to.node == rid }
                droppedEdges.addAll(touching)
                host.edges.clear()
                host.edges.addAll(kept)
            }
        }
        val internalRuntimeIds = newMap.values.toSet()
        host.edges.removeAll { e ->
            e.from.node in internalRuntimeIds && e.to.node in internalRuntimeIds
        }
        for (e in newTemplate.edges) {
            val from = newMap[e.from.node] ?: continue
            val to = newMap[e.to.node] ?: continue
            host.edges.add(Edge(PinRef(from, e.from.pin), PinRef(to, e.to.pin)))
        }
        val members = mutableListOf<MemberRef>()
        for ((_, rid) in newMap) members.add(MemberRef.Node(rid))
        host.groups[idx] = cur.copy(members = members, templateIdMap = newMap)
        return ResolveResult(groupId, droppedEdges)
    }
}
