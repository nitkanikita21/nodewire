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
        // Pin links survive Sable schematic copy-paste (blueprint mapper). Run at
        // common setup so the BE types are registered + .get()-able; internally
        // gated on the sable_schematic_api mod, so it's a safe no-op without it.
        MOD_BUS.addListener<net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent> {
            dev.nitka.nodewire.integration.sable.SableSchematicIntegration.register()
        }
        // Coarse server-side teardown: cancel every server script coroutine when
        // the (integrated or dedicated) server stops. Per-BE setRemoved handles
        // the common case, but a server stop without unloading each BE would leave
        // per-node scopes lingering on the shared daemon pool until GC; cancel them
        // up-front so a singleplayer quit→rejoin in the same JVM starts clean.
        FORGE_BUS.addListener<net.neoforged.neoforge.event.server.ServerStoppingEvent> {
            dev.nitka.nodewire.script.ScriptNodeRuntime.cancelAll()
            // Drop every transmitter snapshot so a singleplayer quit→rejoin in the
            // same JVM can't resurrect ghost broadcasts from the old world.
            dev.nitka.nodewire.radio.RadioRegistry.clearAll()
            // Drop far-camera chunk-stream tracking (Pillar 2 Stage A).
            dev.nitka.nodewire.camerachunk.CameraChunkServer.clearAll()
        }
        // Pillar 2 Stage A — far-camera chunk streaming: per server-player tick
        // refreshes force-load tickets + flushes loaded zone chunks; logout releases.
        FORGE_BUS.addListener<net.neoforged.neoforge.event.tick.PlayerTickEvent.Post> { e ->
            (e.entity as? net.minecraft.server.level.ServerPlayer)?.let {
                dev.nitka.nodewire.camerachunk.CameraChunkServer.tick(it)
            }
        }
        FORGE_BUS.addListener<net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent> { e ->
            (e.entity as? net.minecraft.server.level.ServerPlayer)?.let {
                dev.nitka.nodewire.camerachunk.CameraChunkServer.onLeave(it)
            }
        }
        // Per-dimension unload (e.g. a Sable plot world / End closing) — purge just
        // that dimension's transmitters so the registry can't leak across reloads.
        FORGE_BUS.addListener<net.neoforged.neoforge.event.level.LevelEvent.Unload> { e ->
            val lvl = e.level as? net.minecraft.world.level.Level ?: return@addListener
            if (!lvl.isClientSide) dev.nitka.nodewire.radio.RadioRegistry.clear(lvl.dimension())
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
