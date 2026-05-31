package dev.nitka.nodewire.script

import net.minecraft.nbt.CompoundTag

/**
 * Host-side (de)serialization for replicated state deltas. Reuses the EXACT
 * per-[StateKind] NBT shape as [saveState]/[loadState] so REDSTONE power-clamp
 * and every kind stay byte-faithful with persistence (server→client delta and
 * on-disk persistence agree). Server-side; never sandbox-visible.
 */
object ScriptModuleReplication {

    /** One [ScriptModule.ReplicatedDelta] -> a single-key CompoundTag (key = cell key). */
    fun encodeCell(delta: ScriptModule.ReplicatedDelta): CompoundTag {
        val tag = CompoundTag()
        when (delta.kind) {
            StateKind.INT -> tag.putInt(delta.key, delta.value as Int)
            StateKind.FLOAT -> tag.putFloat(delta.key, delta.value as Float)
            StateKind.BOOL -> tag.putBoolean(delta.key, delta.value as Boolean)
            StateKind.STRING -> tag.putString(delta.key, delta.value as String)
            StateKind.REDSTONE -> tag.putInt(delta.key, (delta.value as Redstone).power)
            StateKind.VIDEO -> tag.putUUID(delta.key, (delta.value as Video).handle)
        }
        return tag
    }

    /** Merge a multi-key tag of cell values into [module]'s state cells. Reuses
     *  [loadState] verbatim (it merges only keys present in the tag). */
    fun applyCells(module: ScriptModule, tag: CompoundTag) = module.loadState(tag)

    /**
     * Late-joiner piggyback (2b.3b): build the replicated-only sub-tag of the
     * CURRENT cell values, read from a node's full persisted [nodeState] tag.
     * Filters to [replicatedKeys] so server-only cells never leak to clients and
     * the chunk-sync tag stays tiny. Pure — unit-tested in isolation.
     *
     * [nodeState] is `saveState`-shaped (every persisted cell), so the value for
     * each replicated key is copied across with its existing NBT element type.
     */
    fun buildReplicatedSubTag(nodeState: CompoundTag, replicatedKeys: List<String>): CompoundTag {
        val out = CompoundTag()
        for (key in replicatedKeys) {
            val element = nodeState.get(key) ?: continue
            out.put(key, element.copy())
        }
        return out
    }
}
