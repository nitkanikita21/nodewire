package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import net.minecraftforge.fml.common.Mod
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Nodewire.ID)
object Nodewire {
    const val ID = "nodewire"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        Registry.register(MOD_BUS)
        LOG.info("Nodewire loading")
    }
}
