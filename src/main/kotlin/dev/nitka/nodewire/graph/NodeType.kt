package dev.nitka.nodewire.graph

import androidx.compose.runtime.Composable
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * Palette grouping. Drives the section headers in the node-picker UI and
 * has no semantic meaning beyond display order — moving a type between
 * categories doesn't break saved graphs.
 */
enum class NodeCategory(val displayName: String) {
    IO("I/O"),
    LOGIC("Logic"),
    MATH("Math"),
    CONSTANTS("Constants"),
}

/**
 * Metadata describing a kind of node — its pin layout, display name, palette
 * category, and a factory for its default config blob. One [NodeType] per
 * registered "what does AND do, what pins does it have" definition;
 * instances are [Node]s created via [newInstance].
 *
 * Pins are stored on the [NodeType] (immutable per-type) and copied into
 * each [Node] instance so the graph format remains self-describing — a
 * graph loaded with an unregistered [id] won't render or evaluate but
 * round-trips losslessly.
 */
data class NodeType(
    val id: ResourceLocation,
    val displayName: String,
    val category: NodeCategory,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val defaultConfig: () -> CompoundTag = { CompoundTag() },
    /**
     * Optional in-card configuration UI. When non-null, [NodeCard] renders
     * this between the title bar and the pin rows. The composable receives
     * the [Node] instance so it can read/write [Node.config] directly —
     * mutations should also bump a Compose state to trigger recomposition.
     */
    val configContent: (@Composable (Node) -> Unit)? = null,
) {
    /** Instantiate a fresh [Node] at [pos] with default config. */
    fun newInstance(pos: CanvasPos = CanvasPos.Zero): Node =
        Node(
            id = Node.newId(),
            typeKey = id,
            pos = pos,
            inputs = inputs,
            outputs = outputs,
            config = defaultConfig(),
        )
}
