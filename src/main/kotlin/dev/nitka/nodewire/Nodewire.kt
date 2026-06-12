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
        // CBC ballistics for scripts (Cbc.shells()/solvePitch) + cannon-mount
        // yaw/pitch pins. Internally ModList-gated — safe no-op without CBC.
        dev.nitka.nodewire.integration.cbc.CbcIntegration.init()
        // Coarse server-side teardown: cancel every server script coroutine when
        // the (integrated or dedicated) server stops. Per-BE setRemoved handles
        // the common case, but a server stop without unloading each BE would leave
        // per-node scopes lingering on the shared daemon pool until GC; cancel them
        // up-front so a singleplayer quit→rejoin in the same JVM starts clean.
        FORGE_BUS.addListener<net.neoforged.neoforge.event.server.ServerStoppingEvent> {
            dev.nitka.nodewire.script.ScriptNodeRuntime.cancelAll()
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
