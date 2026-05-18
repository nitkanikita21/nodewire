package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.command.HighlightServerCommand
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.graph.StockNodeTypes
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
        Registry.register(MOD_BUS)
        StockNodeTypes.registerAll()
        // NodewireNetwork is an @EventBusSubscriber — registration happens
        // automatically via the event bus; no manual call needed.
        EndpointBackends.register(WorldBackend)
        FORGE_BUS.addListener(HighlightServerCommand::register)
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.nitka.nodewire.client.NodewireClient.registerOnModBus(MOD_BUS)
        }
        LOG.info("Nodewire loading")
    }
}
