package dev.nitka.nodewire.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import dev.nitka.nodewire.client.command.HighlightCommand
import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import dev.nitka.nodewire.client.script.ClientScriptCommand
import dev.nitka.nodewire.client.screen.ScreenBlockRenderer
import dev.nitka.nodewire.client.script.ClientScriptDriver
import dev.nitka.nodewire.client.video.VideoManager
import dev.nitka.nodewire.client.wire.WireWorldRenderer
import dev.nitka.nodewire.ui.dev.DemoScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.event.level.LevelEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

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
        // First BER in the repo: the video Screen face. MOD bus.
        bus.addListener<net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers> { event ->
            event.registerBlockEntityRenderer(
                dev.nitka.nodewire.Registry.SCREEN_BLOCK_BE.get(),
                ::ScreenBlockRenderer,
            )
        }
        FORGE_BUS.addListener(::onClientTick)
        FORGE_BUS.addListener<RenderLevelStageEvent>(WireWorldRenderer::render)
        FORGE_BUS.addListener<RenderLevelStageEvent>(BlockHighlightRenderer::onRender)
        // Phase 2c — CLIENT script frame driver (ONE stage; guards double-fire
        // internally) + the `/nodewire clientscripts <on|off>` kill-switch.
        FORGE_BUS.addListener<RenderLevelStageEvent>(ClientScriptDriver::onRenderLevelStage)
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(ClientScriptCommand::register)
        FORGE_BUS.addListener(::onLevelUnload)
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(HighlightCommand::register)
        LOG.info("Nodewire client handlers registered (MOD bus + FORGE bus)")
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        // Video handle GC sweep — runs every client tick regardless of whether a
        // screen is open (the early-return below only gates the dev keybind).
        VideoManager.onClientTick()
        if (Minecraft.getInstance().screen != null) return
        if (OPEN_DEMO_KEY.consumeClick()) {
            LOG.info("Opening DemoScreen")
            Minecraft.getInstance().setScreen(DemoScreen())
        }
    }

    /**
     * Phase 2c — cancel all CLIENT script runtimes when the client level
     * unloads (dimension change / disconnect). Per-BE unload is handled in
     * [dev.nitka.nodewire.block.LogicBlockEntity.setRemoved]; this is the
     * coarse level-wide net so nothing leaks across a level swap.
     */
    private fun onLevelUnload(event: LevelEvent.Unload) {
        if (!event.level.isClientSide) return
        ClientScriptDriver.onLevelUnload()
    }
}
