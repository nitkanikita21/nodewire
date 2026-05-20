package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.neoforged.fml.ModList

/**
 * Tuple of fields uniquely identifying one (source block, channel)
 * binding. Two `aeronautics_input` nodes with identical config produce
 * the same key and read the same value — which is correct: same source +
 * channel → same value.
 */
data class AeroBindingKey(
    val endpoint: EndpointRef,
    val kind: AeroBlockKind,
    val channel: AeroChannel,
)

/**
 * Server-tick state publisher for `aeronautics_input` nodes.
 *
 * Per tick: [snapshot] walks the graph, resolves each bound endpoint,
 * confirms the resolved BE matches the configured kind, reads the
 * channel value via [AeroChannel.read], and emits a per-key map. The
 * map is published to [currentValues] before evaluator dispatch and
 * restored after — same ThreadLocal pattern as
 * `ControllerInputNode.currentState`.
 *
 * Mod-absent: short-circuits to empty map when Aeronautics isn't loaded.
 * The [aeronauticsLoaded] parameter is injectable so unit tests exercise
 * the guard without bootstrapping MC.
 *
 * Read failures (renamed field, removed method, unexpected null) are
 * swallowed per-node via runCatching — one broken binding cannot crash
 * the whole eval.
 */
object AeroStatePipeline {

    val currentValues: ThreadLocal<Map<AeroBindingKey, PinValue>> =
        ThreadLocal.withInitial { emptyMap() }

    private val runtimeAeronauticsLoaded: Boolean by lazy {
        runCatching { ModList.get().isLoaded("aeronautics") }.getOrDefault(false)
    }

    fun snapshot(
        level: Level?,
        graph: NodeGraph,
        aeronauticsLoaded: Boolean = runtimeAeronauticsLoaded,
    ): Map<AeroBindingKey, PinValue> {
        if (!aeronauticsLoaded) return emptyMap()
        val out = HashMap<AeroBindingKey, PinValue>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "aeronautics_input") continue
            val key = keyFromConfig(node.config) ?: continue
            if (key.channel.kind != key.kind) continue       // stale config
            val resolvedLevel = level ?: continue
            val be = key.endpoint.resolve(resolvedLevel) ?: continue
            if (!key.kind.matches(be)) continue              // moved / replaced
            val value = runCatching { key.channel.read(be) }.getOrNull() ?: continue
            out[key] = value
        }
        return out
    }

    /**
     * Reconstructs the lookup key from a node's config CompoundTag.
     * Returns null when the binding is incomplete or references unknown
     * names (forward-compat with future kinds / channels).
     */
    fun keyFromConfig(config: CompoundTag): AeroBindingKey? {
        if (!config.contains("endpoint")) return null
        val endpointTag = config.getCompound("endpoint")
        val endpoint = EndpointRef.CODEC
            .parse(net.minecraft.nbt.NbtOps.INSTANCE, endpointTag)
            .result().orElse(null) ?: return null
        val kind = AeroBlockKind.fromName(config.getString("blockKind")) ?: return null
        val channel = AeroChannel.fromName(config.getString("channel")) ?: return null
        return AeroBindingKey(endpoint, kind, channel)
    }
}
