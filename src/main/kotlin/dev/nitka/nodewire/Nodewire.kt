package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.command.HighlightServerCommand
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.graph.StockNodeTypes
import dev.nitka.nodewire.integration.sable.SableSubLevelBackend
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(Nodewire.ID)
object Nodewire {
    const val ID = "nodewire"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        // Open kotlin.stdlib -> kotlinx.coroutines.core reads edge BEFORE any
        // coroutine launches anywhere in the mod (NwUiOwner.start, etc.).
        JpmsBridge.openCoroutinesDebugBridge()
        Registry.register(MOD_BUS)
        StockNodeTypes.registerAll()
        // NodewireNetwork is an @EventBusSubscriber — registration happens
        // automatically via the event bus; no manual call needed.
        // Sable backend before WorldBackend — claims() probes in registration
        // order and WorldBackend always wins as the fallback. Companion's safe
        // defaults make this no-op when Sable itself isn't loaded.
        SableSubLevelBackend.register()
        EndpointBackends.register(WorldBackend)
        if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            dev.nitka.nodewire.integration.cctweaked.NwPeripheralCapability.register(MOD_BUS)
        }
        FORGE_BUS.addListener(HighlightServerCommand::register)
        FORGE_BUS.addListener(dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler::onRightClickItem)
        FORGE_BUS.addListener(dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler::onRightClickBlock)
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.nitka.nodewire.client.NodewireClient.registerOnModBus(MOD_BUS)
        }
        LOG.info("Nodewire loading")
    }
}
