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
        if (rootFile == insertedTemplate) return true
        // BFS from inserted template; if we ever encounter rootFile, cycle.
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(insertedTemplate)
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
