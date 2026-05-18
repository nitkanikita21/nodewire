package dev.nitka.nodewire.integration.create

import com.simibubi.create.Create
import com.simibubi.create.content.redstone.link.IRedstoneLinkable
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency
import dev.nitka.nodewire.block.LogicBlockEntity
import net.createmod.catnip.data.Couple
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * All Create-API contact for the redstone-link integration lives here.
 *
 * Callers in core code must gate calls with `ModList.isLoaded("create")`;
 * this object itself doesn't check — invoking any function commits you to
 * Create being on the classpath at runtime.
 *
 * Design: both input and output nodes register a [NodeLinkable] adapter
 * per (BE, nodeId). The adapter's [NodeLinkable.listening] flag controls
 * whether Create pushes received signals via `setReceivedStrength` (input
 * nodes) or only treats it as a transmitter (output nodes).
 */
object CreateRedstoneLink {

    /** Decode a `Couple<Frequency>` from a node's config tag (`freq1`, `freq2`). */
    fun frequencyOf(cfg: CompoundTag, level: Level): Couple<Frequency> {
        val ra = level.registryAccess()
        val s1 = ItemStack.parse(ra, cfg.getCompound("freq1")).orElse(ItemStack.EMPTY)
        val s2 = ItemStack.parse(ra, cfg.getCompound("freq2")).orElse(ItemStack.EMPTY)
        return Couple.create(Frequency.of(s1), Frequency.of(s2))
    }

    /**
     * Single adapter for both input (`listening=true`) and output
     * (`listening=false`) nodes. For input nodes Create writes the latest
     * received signal into [lastReceived] via [setReceivedStrength]; the
     * server-tick reads it and forwards to the evaluator. For output nodes
     * the server tick writes [lastTransmit] from the evaluator's incoming
     * edge value and calls [updateNetworkOf].
     */
    class NodeLinkable(
        private val be: LogicBlockEntity,
        @Volatile var freq: Couple<Frequency>,
        val listening: Boolean,
    ) : IRedstoneLinkable {
        @Volatile var lastTransmit: Int = 0
        @Volatile var lastReceived: Int = 0

        override fun getNetworkKey(): Couple<Frequency> = freq
        override fun getTransmittedStrength(): Int = lastTransmit
        override fun setReceivedStrength(value: Int) { lastReceived = value }
        override fun isListening(): Boolean = listening
        override fun isAlive(): Boolean = !be.isRemoved
        override fun getLocation(): BlockPos = be.blockPos
    }

    fun register(level: Level, linkable: NodeLinkable) {
        handler()?.addToNetwork(level, linkable)
    }

    fun unregister(level: Level, linkable: NodeLinkable) {
        handler()?.removeFromNetwork(level, linkable)
    }

    fun updateNetworkOf(level: Level, linkable: NodeLinkable) {
        handler()?.updateNetworkOf(level, linkable)
    }

    private fun handler(): RedstoneLinkNetworkHandler? = Create.REDSTONE_LINK_NETWORK_HANDLER
}
