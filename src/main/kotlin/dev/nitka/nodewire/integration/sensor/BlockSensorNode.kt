package dev.nitka.nodewire.integration.sensor

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
 * `block_sensor` — surfaces one [SensorReading] of a bound world block as a
 * single typed output pin. Mirror of [AeroInputNode], but the substrate is
 * VANILLA NeoForge caps (`Capabilities.ItemHandler.BLOCK` /
 * `Capabilities.FluidHandler.BLOCK`) + `BlockState.getAnalogOutputSignal` —
 * so there is NO `ModList` guard anywhere.
 *
 * Config:
 *   * `endpoint`: CompoundTag — serialized [dev.nitka.nodewire.endpoint.EndpointRef]
 *     pointing at the source block. Absent means unbound — node emits the
 *     type default.
 *   * `reading`: String — [SensorReading] name (defaults to ITEM_COUNT).
 *   * `side`: String — `Direction.name` of the side the cap is read from;
 *     absent ⇒ null (un-sided lookup). Lives in node config, not EndpointRef.
 *   * `filter`: ItemStack tag — only used by filtered readings; absent otherwise.
 *
 * Output: one pin `"out"` whose [PinType] is dictated by the configured
 * reading. The default pin layout matches the default reading
 * (ITEM_COUNT → INT).
 *
 * The evaluator looks up the current value in
 * [SensorStatePipeline.currentValues] using a key reconstructed from this
 * node's config. When the source resolves, the snapshot has the value;
 * otherwise the evaluator falls back to [PinValue.default] of the reading's
 * pin type.
 */
object BlockSensorNode {

    val Evaluator: NodeEvaluator = { config, _ ->
        val reading = SensorReading.fromName(config.getString("reading"))
        val pinType = reading?.pinType ?: PinType.INT
        val key = SensorStatePipeline.keyFromConfig(config)
        val value = key?.let { SensorStatePipeline.currentValues.get()[it] }
            ?: PinValue.default(pinType)
        mapOf("out" to value)
    }

    val BLOCK_SENSOR: NodeType = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "block_sensor"),
        displayName = "🔍 Block Sensor",
        category = NodeCategory.IO,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.INT)),
        defaultConfig = {
            CompoundTag().apply {
                putString("reading", SensorReading.ITEM_COUNT.name)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.BlockSensor,
        evaluate = Evaluator,
        pinReshape = { config ->
            val readingName = config.getString("reading")
                .ifEmpty { SensorReading.ITEM_COUNT.name }
            val reading = SensorReading.fromName(readingName) ?: SensorReading.ITEM_COUNT
            pinsFor(reading)
        },
    )

    /**
     * Computes the (inputs, outputs) pin layout for a given reading.
     * Called by `EditorState.changeSensorReading` (T8) when the user
     * changes the reading — mirrors [AeroInputNode.pinsFor].
     */
    fun pinsFor(reading: SensorReading): Pair<List<Pin>, List<Pin>> {
        val out = Pin("out", "Out", reading.pinType)
        return emptyList<Pin>() to listOf(out)
    }
}
