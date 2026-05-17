package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.command.HighlightServerCommand
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.graph.StockNodeTypes
import dev.nitka.nodewire.net.NodewireNetwork
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Nodewire.ID)
object Nodewire {
    const val ID = "nodewire"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        Registry.register(MOD_BUS)
        StockNodeTypes.registerAll()
        NodewireNetwork.register()
        if (ModList.get().isLoaded("valkyrienskies")) {
            dev.nitka.nodewire.integration.vs.VsShipBackend.register()
        }
        EndpointBackends.register(WorldBackend)
        FORGE_BUS.addListener<RegisterCommandsEvent>(HighlightServerCommand::register)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler,
        )
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.nitka.nodewire.client.NodewireClient.registerOnModBus(MOD_BUS)
        }
        LOG.info("Nodewire loading")
    }
}
