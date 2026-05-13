package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Nodewire.ID)
object Nodewire {
    const val ID = "nodewire"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        Registry.register(MOD_BUS)
        StockNodeTypes.registerAll()
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.nitka.nodewire.client.NodewireClient.registerOnModBus(MOD_BUS)
        }
        LOG.info("Nodewire loading")
    }
}
