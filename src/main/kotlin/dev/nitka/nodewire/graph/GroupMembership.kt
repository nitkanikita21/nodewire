package dev.nitka.nodewire.graph

/**
 * Pure helpers for navigating group structure: ancestors, descendants,
 * and the all-important cycle check that protects us from infinite
 * recursion when a template instantiates another that (transitively)
 * instantiates the first.
 */
object GroupMembership {

    /**
     * True if inserting a group with `insertedTemplate` into a host that
     * currently sits inside (or IS) `rootFile`'s template would create a
     * cycle.
     *
     * `resolve` returns the template content for a given filename, or
     * `null` if the file is missing. Missing files conservatively count
     * as cycle-safe — we can't see what they reference.
     */
    fun wouldCycle(
        rootFile: String?,
        insertedTemplate: String,
        resolve: (String) -> GroupTemplate?,
    ): Boolean {
        if (rootFile == null) return false
        // BFS from inserted template's CHILDREN — `insertedTemplate` itself
        // being named the same as `rootFile` is not a cycle on its own;
        // only a DESCENDANT reference back to `rootFile` (or to
        // `insertedTemplate` itself, when called with rootFile=safe to
        // self-check) is. Without this, dropping two independent
        // instances of the same template onto one graph would be falsely
        // rejected as a cycle.
        val start = resolve(insertedTemplate) ?: return false
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (g in start.groups) g.templateFile?.let { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            if (cur == rootFile) return true
            val tpl = resolve(cur) ?: continue
            for (g in tpl.groups) {
                val ref = g.templateFile ?: continue
                queue.addLast(ref)
            }
        }
        return false
    }
}
