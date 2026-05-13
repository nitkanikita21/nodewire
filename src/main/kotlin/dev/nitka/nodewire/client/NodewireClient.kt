package dev.nitka.nodewire.client

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.ui.dev.DemoScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW

/**
 * Client-only setup. Registers the `N` keybind for opening [DemoScreen] in
 * dev — temporary scaffolding until Phase 11+ adds a proper "open logic block
 * editor" flow via right-click on the block.
 *
 * Two event buses are used:
 *   - MOD bus (KeyMappings registration — fires once during mod loading)
 *   - FORGE bus (per-tick check of consumeClick() — fires every client tick
 *     while a level is loaded)
 */
@Mod.EventBusSubscriber(modid = Nodewire.ID, value = [Dist.CLIENT])
object NodewireClient {

    private val OPEN_DEMO_KEY = KeyMapping(
        "key.nodewire.open_demo",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_N,
        "key.categories.nodewire",
    )

    @SubscribeEvent
    @JvmStatic
    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(OPEN_DEMO_KEY)
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (Minecraft.getInstance().screen != null) return
        if (OPEN_DEMO_KEY.consumeClick()) {
            Minecraft.getInstance().setScreen(DemoScreen())
        }
    }
}
