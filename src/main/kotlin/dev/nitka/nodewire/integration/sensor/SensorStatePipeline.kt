package dev.nitka.nodewire.integration.sensor

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Identity of one (source block, side, reading, filter) binding. [filterId]
 * is the filter item's registry-name string (count/components ignored, per
 * spec) so the key stays value-equatable for the snapshot HashMap.
 */
data class SensorBindingKey(
    val endpoint: EndpointRef,
    val reading: SensorReading,
    val side: Direction?,
    val filterId: String,
)

/**
 * Server-tick state publisher for `block_sensor` nodes. Mirror of
 * AeroStatePipeline; NO ModList guard (vanilla caps). Per tick [snapshot]
 * walks the graph, resolves each bound endpoint, reads the configured
 * reading via the vanilla cap, and publishes a per-key map to
 * [currentValues] for the evaluator's O(1) lookup. Every read is wrapped in
 * runCatching -> PinValue.default so one bad block never crashes eval.
 */
object SensorStatePipeline {

    val currentValues: ThreadLocal<Map<SensorBindingKey, PinValue>> =
        ThreadLocal.withInitial { emptyMap() }

    fun snapshot(level: Level?, graph: NodeGraph): Map<SensorBindingKey, PinValue> {
        val resolvedLevel = level ?: return emptyMap()
        val out = HashMap<SensorBindingKey, PinValue>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "block_sensor") continue
            val key = keyFromConfig(node.config) ?: continue
            if (out.containsKey(key)) continue
            val be = key.endpoint.resolve(resolvedLevel) ?: continue
            val filter = filterFromConfig(node.config, resolvedLevel)
            val value = runCatching { key.reading.read(be, key.side, filter) }
                .getOrElse { PinValue.default(key.reading.pinType) }
            out[key] = value
        }
        return out
    }

    /** Reconstructs the lookup key from a node's config. Null when unbound/bad. */
    fun keyFromConfig(config: CompoundTag): SensorBindingKey? {
        if (!config.contains("endpoint")) return null
        val endpointTag = config.getCompound("endpoint")
        val endpoint = EndpointRef.CODEC
            .parse(NbtOps.INSTANCE, endpointTag)
            .result().orElse(null) ?: return null
        val reading = SensorReading.fromName(config.getString("reading")) ?: return null
        val side = sideFromConfig(config)
        val filterId = filterIdFromConfig(config)
        return SensorBindingKey(endpoint, reading, side, filterId)
    }

    fun sideFromConfig(config: CompoundTag): Direction? =
        if (config.contains("side")) Direction.byName(config.getString("side").lowercase()) else null

    /** Registry-name of the filter item, or "" when unset. */
    private fun filterIdFromConfig(config: CompoundTag): String {
        val stack = rawFilter(config) ?: return ""
        return if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }

    /** Full ItemStack filter (needs a registry provider for component parse). */
    private fun filterFromConfig(config: CompoundTag, level: Level): ItemStack? {
        if (!config.contains("filter")) return null
        return ItemStack.parse(level.registryAccess(), config.getCompound("filter")).orElse(ItemStack.EMPTY)
    }

    private fun rawFilter(config: CompoundTag): ItemStack? {
        if (!config.contains("filter")) return null
        // Item id lives under the standard "id" key of a saved ItemStack tag;
        // a registry-free read is enough for the key (full parse needs a provider).
        val tag = config.getCompound("filter")
        val idStr = tag.getString("id")
        if (idStr.isEmpty()) return null
        val loc = ResourceLocation.tryParse(idStr) ?: return null
        val item = BuiltInRegistries.ITEM.getOptional(loc).orElse(null) ?: return null
        return ItemStack(item)
    }
}
