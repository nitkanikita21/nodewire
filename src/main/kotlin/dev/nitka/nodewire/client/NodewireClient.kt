package dev.nitka.nodewire.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import dev.nitka.nodewire.client.wire.WireWorldRenderer
import dev.nitka.nodewire.ui.dev.DemoScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

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
object NodewireClient {
    private val LOG = LogUtils.getLogger()

    private val OPEN_DEMO_KEY = KeyMapping(
        "key.nodewire.open_demo",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_N,
        "key.categories.nodewire",
    )

    fun registerOnModBus(bus: IEventBus) {
        bus.addListener<RegisterKeyMappingsEvent> { it.register(OPEN_DEMO_KEY) }
        FORGE_BUS.addListener(::onClientTick)
        FORGE_BUS.addListener<RenderLevelStageEvent>(WireWorldRenderer::render)
        LOG.info("Nodewire client handlers registered (MOD bus + FORGE bus)")
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (Minecraft.getInstance().screen != null) return
        if (OPEN_DEMO_KEY.consumeClick()) {
            LOG.info("Opening DemoScreen")
            Minecraft.getInstance().setScreen(DemoScreen())
        }
    }
}
