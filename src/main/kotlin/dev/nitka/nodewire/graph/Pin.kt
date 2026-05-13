package dev.nitka.nodewire.graph

/**
 * A single typed slot on a node. [id] is unique within its parent node's
 * input list (or output list) and is what [PinRef] uses to address the
 * pin — display [name] is for the UI and can be changed without breaking
 * saved graphs.
 *
 * Input vs output is determined by which list of [Node] the pin lives in,
 * not by a field here.
 */
data class Pin(
    val id: String,
    val name: String,
    val type: PinType,
)
