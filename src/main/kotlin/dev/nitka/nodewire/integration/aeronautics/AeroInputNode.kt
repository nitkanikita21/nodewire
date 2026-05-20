package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeEvaluator
import dev.nitka.nodewire.graph.NodeType
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * `aeronautics_input` — surfaces one [AeroChannel] reading from a bound
 * Aeronautics block as a single typed output pin.
 *
 * Config:
 *   * `endpoint`: CompoundTag — serialized [dev.nitka.nodewire.endpoint.EndpointRef]
 *     pointing at the source block. Absent means unbound — node emits zero.
 *   * `blockKind`: String — [AeroBlockKind] name (defaults to SMART_PROPELLER).
 *   * `channel`: String — [AeroChannel] name (defaults to PROP_ROTATION_SPEED).
 *
 * Output: one pin `"out"` whose [PinType] is dictated by the configured
 * channel. The default pin layout matches the default channel
 * (PROP_ROTATION_SPEED → FLOAT). Edits via
 * `EditorState.changeAeroChannel` rewrite the pin list AND disconnect any
 * edges whose types no longer match.
 *
 * The evaluator looks up the current value in
 * [AeroStatePipeline.currentValues] using a key reconstructed from this
 * node's config. When the source resolves (and Aeronautics is loaded),
 * the snapshot has the value; otherwise the evaluator falls back to
 * [PinValue.default] of the channel's pin type.
 */
object AeroInputNode {

    val Evaluator: NodeEvaluator = { config, _ ->
        val channel = AeroChannel.fromName(config.getString("channel"))
        val pinType = channel?.pinType ?: PinType.FLOAT
        val key = AeroStatePipeline.keyFromConfig(config)
        val value = key?.let { AeroStatePipeline.currentValues.get()[it] }
            ?: PinValue.default(pinType)
        mapOf("out" to value)
    }

    val AERONAUTICS_INPUT: NodeType = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "aeronautics_input"),
        displayName = "✈ Aeronautics Input",
        category = NodeCategory.IO,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        defaultConfig = {
            CompoundTag().apply {
                putString("blockKind", AeroBlockKind.SMART_PROPELLER.name)
                putString("channel", AeroChannel.PROP_ROTATION_SPEED.name)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.AeroInput,
        evaluate = Evaluator,
    )

    /**
     * Computes the (inputs, outputs) pin layout for a given channel.
     * Called by `EditorState.changeAeroChannel` (Aero T5) when the user
     * changes the channel — mirrors `pinsForVecOp`.
     */
    fun pinsFor(channel: AeroChannel): Pair<List<Pin>, List<Pin>> {
        val out = Pin("out", "Out", channel.pinType)
        return emptyList<Pin>() to listOf(out)
    }
}
