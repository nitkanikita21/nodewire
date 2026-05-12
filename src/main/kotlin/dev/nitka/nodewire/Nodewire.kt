package dev.nitka.nodewire

import com.mojang.logging.LogUtils
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.slf4j.Logger

@Mod(Nodewire.ID)
object Nodewire {
    const val ID = "nodewire"
    val LOG: Logger = LogUtils.getLogger()

    init {
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        Registry.register(bus)
        LOG.info("Nodewire loading")
    }
}
