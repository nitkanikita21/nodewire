package dev.nitka.nodewire.integration.sable

import dev.nitka.nodewire.Registry
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.fml.ModList

/**
 * Wires [SablePinLinkMapper] into the Sable Schematic API so pin links survive
 * blueprint copy-paste. No-op (and never touches the API classes) unless
 * `sable_schematic_api` is installed.
 *
 * Called from a common-setup hook — by then the BlockEntity types are
 * registered and `.get()` is safe.
 */
object SableSchematicIntegration {

    fun register() {
        if (!ModList.get().isLoaded("sable_schematic_api")) return
        // Every PinLinkSink BE persists `pin_links`; register the mapper for each.
        val types: List<BlockEntityType<*>> = listOf(
            Registry.LOGIC_BLOCK_BE.get(),
            Registry.SCREEN_BLOCK_BE.get(),
            Registry.CAMERA_BLOCK_BE.get(),
            Registry.TELEMETRY_BLOCK_BE.get(),
            Registry.RADIO_RECEIVER_BE.get(),
            Registry.RADIO_TRANSMITTER_BE.get(),
            Registry.AR_HUB_BE.get(),
        )
        for (type in types) {
            SableBlueprintMapperRegistry.register(type, SablePinLinkMapper)
        }
        // Level-hosted links have no BE to ride; the blueprint-level event mapper
        // collects/rebuilds them at save/place time.
        SableBlueprintEventRegistry.register(SableHostlessLinkMapper)
    }
}
