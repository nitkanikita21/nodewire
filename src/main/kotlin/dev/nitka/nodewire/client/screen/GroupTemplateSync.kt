package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import dev.nitka.nodewire.graph.GroupTemplateResolver
import dev.nitka.nodewire.graph.NodeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Glues [GroupTemplateStore] to disk ([GroupFiles]) and to a host
 * [EditorState]'s reactive graph. Per-template-file responsibilities:
 *
 *   * On first call to [observeFile], hydrate the flow from disk if it
 *     still holds `null`.
 *   * Subscribe to the flow and re-apply each non-null publication to
 *     every linked instance in the host graph via
 *     [GroupTemplateResolver.applyTemplateChange]. Dropped external
 *     edges are reported to [onEdgeDropped] (toast).
 *   * Provide [publishLocalEdit] for callers that have just mutated an
 *     instance; debounces a disk write so rapid keystrokes don't
 *     hammer the filesystem.
 */
class GroupTemplateSync(
    private val store: GroupTemplateStore,
    private val scope: CoroutineScope,
    private val editor: EditorState,
    private val onEdgeDropped: (Int) -> Unit = {},
) {

    private val writeJobs = HashMap<String, Job>()
    private val observed = HashSet<String>()

    /** Begin watching [file] for this editor session. Idempotent. */
    fun observeFile(file: String) {
        if (!observed.add(file)) return
        if (store.current(file) == null) {
            val loaded = GroupFiles.load(file)
            if (loaded != null) store.publish(file, loaded)
        }
        scope.launch {
            store.flowOf(file).filterNotNull().collect { newTemplate ->
                applyToInstances(file, newTemplate)
            }
        }
    }

    private fun applyToInstances(file: String, template: GroupTemplate) {
        val graph: NodeGraph = editor.graph
        val targets = graph.groups.filter { it.templateFile == file }.map { it.id }
        var dropped = 0
        editor.mutateGraph(mergeable = false) {
            for (gid in targets) {
                val res = GroupTemplateResolver.applyTemplateChange(graph, gid, template) { other ->
                    GroupFiles.load(other) ?: store.current(other)
                }
                dropped += res.droppedEdges.size
            }
        }
        if (dropped > 0) onEdgeDropped(dropped)
    }

    /** Push a local edit into the store + schedule disk write (300 ms debounce). */
    fun publishLocalEdit(file: String, template: GroupTemplate) {
        store.publish(file, template)
        writeJobs[file]?.cancel()
        writeJobs[file] = scope.launch {
            delay(WRITE_DEBOUNCE_MS)
            GroupFiles.save(file, template)
        }
    }

    companion object {
        private const val WRITE_DEBOUNCE_MS = 300L
    }
}
