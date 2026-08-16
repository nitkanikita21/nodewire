package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.block.control.Binding
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps

/**
 * Binding storage for the `joystick_ctrl` element — a full Control Block
 * embedded in a panel. The element's config carries the SAME [Binding] list a
 * Control Block BE does (same codec, same defaults), so the Control Config
 * editor and [dev.nitka.nodewire.client.control.ControlSession] drive both
 * targets identically.
 */
object PanelControlBindings {

    private const val KEY = "bindings"

    /** The element's binding layout; Control-Block defaults until configured. */
    fun of(config: CompoundTag): List<Binding> {
        if (!config.contains(KEY)) return ControlBlockEntity.defaultBindings()
        return Binding.CODEC.listOf()
            .parse(NbtOps.INSTANCE, config.get(KEY))
            .result().orElseGet { ControlBlockEntity.defaultBindings() }
    }

    /** Copy [config] with the binding list replaced (config-commit path). */
    fun write(config: CompoundTag, bindings: List<Binding>): CompoundTag {
        val out = config.copy()
        Binding.CODEC.listOf()
            .encodeStart(NbtOps.INSTANCE, bindings)
            .result().ifPresent { out.put(KEY, it) }
        return out
    }
}
