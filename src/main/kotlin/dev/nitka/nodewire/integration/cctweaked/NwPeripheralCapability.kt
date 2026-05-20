package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.PeripheralCapability
import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.LogicBlockEntity
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent

/**
 * Registers the [NodewirePeripheral] block capability so wired-modem or
 * adjacency lookups by CC: Tweaked resolve to a fresh instance per
 * `(BE, side)`. CC's caching collapses duplicates via [NodewirePeripheral.equals].
 *
 * Safe to call only when CC: Tweaked is loaded. The caller in
 * [dev.nitka.nodewire.Nodewire.init] gates this with [net.neoforged.fml.ModList].
 */
object NwPeripheralCapability {

    fun register(modBus: IEventBus) {
        modBus.addListener<RegisterCapabilitiesEvent> { event ->
            event.registerBlockEntity(
                PeripheralCapability.get(),
                Registry.LOGIC_BLOCK_BE.get(),
            ) { be: LogicBlockEntity, side ->
                if (side == null) null else NodewirePeripheral(be, side)
            }
        }
    }
}
