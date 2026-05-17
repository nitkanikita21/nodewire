package dev.nitka.nodewire.integration.tweakedcontroller

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * The Tweaked Controller item's "hub" binding lives directly in its NBT.
 * When a player RMBs a Logic Block while holding the controller, the
 * block's [BlockPos] is written here. Subsequent gamepad button/axis
 * packets read the binding from the same NBT slot and push state to
 * that exact block — no UUID lookup, no chunk scan, mirrors how
 * Drive-By-Wire's `HubItem` does it.
 *
 * Forward-compat note: the NBT key is namespaced (`nw:hubPos`) so
 * Nodewire's binding doesn't collide with anything TC writes. NBT
 * survives item move (e.g. controller dropped into a Lectern) so a
 * bound controller still drives its hub even when used via lectern.
 */
object ControllerHubItem {

    private const val NW_HUB_POS_KEY = "nw:hubPos"

    fun putHub(stack: ItemStack, pos: BlockPos) {
        if (stack.isEmpty) return
        val tag: CompoundTag = stack.orCreateTag
        tag.putLong(NW_HUB_POS_KEY, pos.asLong())
    }

    fun getHub(stack: ItemStack): BlockPos? {
        if (stack.isEmpty) return null
        val tag = stack.tag ?: return null
        if (!tag.contains(NW_HUB_POS_KEY)) return null
        return BlockPos.of(tag.getLong(NW_HUB_POS_KEY))
    }

    fun clearHub(stack: ItemStack) {
        if (stack.isEmpty) return
        val tag = stack.tag ?: return
        tag.remove(NW_HUB_POS_KEY)
    }
}
