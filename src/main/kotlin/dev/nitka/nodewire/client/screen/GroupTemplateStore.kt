package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped registry of one [MutableStateFlow] per template file
 * the user has touched. Editors subscribe to a file's flow and react to
 * every publish; this is how an edit in one open instance propagates to
 * other instances in real time.
 *
 * Disk I/O is NOT performed here — the store sits on top of
 * [GroupFiles]. The wiring layer ([GroupTemplateSync]) is responsible
 * for `disk → publish` on first access and `publish → debounced disk
 * write` for outgoing edits.
 */
class GroupTemplateStore {
    private val flows = HashMap<String, MutableStateFlow<GroupTemplate?>>()
    private val readOnly = HashMap<String, StateFlow<GroupTemplate?>>()

    fun flowOf(file: String): StateFlow<GroupTemplate?> {
        val backing = flows.getOrPut(file) { MutableStateFlow(null) }
        return readOnly.getOrPut(file) { backing.asStateFlow() }
    }

    fun publish(file: String, template: GroupTemplate) {
        flows.getOrPut(file) { MutableStateFlow(null) }.value = template
    }

    fun current(file: String): GroupTemplate? = flows[file]?.value
}
