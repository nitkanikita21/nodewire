package dev.nitka.nodewire.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import dev.nitka.nodewire.client.camera.CameraBlockRenderer
import dev.nitka.nodewire.client.command.HighlightCommand
import dev.nitka.nodewire.client.control.ControlSession
import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import dev.nitka.nodewire.client.panel.ControlPanelBlockRenderer
import dev.nitka.nodewire.client.link.LinkHud
import dev.nitka.nodewire.client.link.LinkHudRenderer
import dev.nitka.nodewire.client.script.ClientScriptCommand
import dev.nitka.nodewire.client.screen.ScreenBlockRenderer
import dev.nitka.nodewire.client.script.ClientScriptDriver
import dev.nitka.nodewire.client.video.ArClientState
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

    /** Control Block: toggle mouse aiming during a piloting session (rebindable). */
    private val CONTROL_MOUSE_KEY = KeyMapping(
        "key.nodewire.control_mouse",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_V,
        "key.categories.nodewire",
    )

    /** Control Block: leave the piloting session. A dedicated key because RMB
     *  (the would-be exit) is among the interactions suppressed while piloting. */
    private val CONTROL_EXIT_KEY = KeyMapping(
        "key.nodewire.control_exit",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        "key.categories.nodewire",
    )

    fun registerOnModBus(bus: IEventBus) {
        bus.addListener<RegisterKeyMappingsEvent> {
            it.register(OPEN_DEMO_KEY)
            it.register(CONTROL_MOUSE_KEY)
            it.register(CONTROL_EXIT_KEY)
        }
        // First BER in the repo: the video Screen face. MOD bus.
        bus.addListener<net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers> { event ->
            event.registerBlockEntityRenderer(
                dev.nitka.nodewire.Registry.SCREEN_BLOCK_BE.get(),
                ::ScreenBlockRenderer,
            )
            // Rotatable camera gimbal (yoke + head moving parts).
            event.registerBlockEntityRenderer(
                dev.nitka.nodewire.Registry.CAMERA_BLOCK_BE.get(),
                ::CameraBlockRenderer,
            )
            // Control Panel: procedural plate + element quads.
            event.registerBlockEntityRenderer(
                dev.nitka.nodewire.Registry.CONTROL_PANEL_BE.get(),
                ::ControlPanelBlockRenderer,
            )
        }
        // Bake the camera's two moving sub-models as standalone models so the
        // BER (and the future Flywheel visual) can fetch them.
        bus.addListener<net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional> { event ->
            event.register(CameraBlockRenderer.YAW_MODEL)
            event.register(CameraBlockRenderer.HEAD_MODEL)
            // Control Panel element bodies (Dashpanels-derived, MIT).
            dev.nitka.nodewire.client.panel.PanelModel.registerAdditional(event)
        }
        // Snapshot the baked panel models once per bake (Dashpanels' hook).
        bus.addListener<net.neoforged.neoforge.client.event.ModelEvent.BakingCompleted> { event ->
            dev.nitka.nodewire.client.panel.PanelModel.bakingCompleted(event)
        }
        // Custom core shader: screen video-noise (signal-strength → grain). If it
        // fails to compile the screen just blits cleanly (instance stays null).
        bus.addListener<net.neoforged.neoforge.client.event.RegisterShadersEvent> { event ->
            runCatching {
                event.registerShader(
                    net.minecraft.client.renderer.ShaderInstance(
                        event.resourceProvider,
                        "nodewire:screen_noise",
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
                    ),
                ) { dev.nitka.nodewire.client.screen.ScreenNoiseShader.instance = it }
            }.onFailure { LOG.warn("screen_noise shader failed to load: {}", it.message) }
            runCatching {
                event.registerShader(
                    net.minecraft.client.renderer.ShaderInstance(
                        event.resourceProvider,
                        "nodewire:screen_crt",
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
                    ),
                ) { dev.nitka.nodewire.client.screen.ScreenCrtShader.instance = it }
            }.onFailure { LOG.warn("screen_crt shader failed to load: {}", it.message) }
        }
        FORGE_BUS.addListener(::onClientTick)
        FORGE_BUS.addListener<RenderLevelStageEvent>(WireWorldRenderer::render)
        // Camera Cable: welding-style snap grid on the aimed face while armed.
        FORGE_BUS.addListener<RenderLevelStageEvent>(
            dev.nitka.nodewire.client.camera.CameraCableOverlay::render,
        )
        // Camera gizmo: lens marker + the ray the camera actually looks along.
        FORGE_BUS.addListener<RenderLevelStageEvent>(
            dev.nitka.nodewire.client.camera.CameraGizmoOverlay::render,
        )
        // Control Panel: outline only the ELEMENT under the crosshair.
        FORGE_BUS.addListener<net.neoforged.neoforge.client.event.RenderHighlightEvent.Block>(
            dev.nitka.nodewire.client.panel.PanelHighlightRenderer::onHighlight,
        )
        FORGE_BUS.addListener<RenderLevelStageEvent>(BlockHighlightRenderer::onRender)
        // Phase 2c — CLIENT script frame driver (ONE stage; guards double-fire
        // internally) + the `/nodewire clientscripts <on|off>` kill-switch.
        FORGE_BUS.addListener<RenderLevelStageEvent>(ClientScriptDriver::onRenderLevelStage)
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(ClientScriptCommand::register)
        FORGE_BUS.addListener(::onLevelUnload)
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(HighlightCommand::register)
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(
            dev.nitka.nodewire.client.command.AimCommand::register,
        )
        // `/nodewire capture <on|off|dump>` — capture loop controls.
        FORGE_BUS.addListener<RegisterClientCommandsEvent>(
            dev.nitka.nodewire.client.camera.harness.CaptureEngine::registerCommand,
        )
        FORGE_BUS.addListener(::onMouseScroll)
        FORGE_BUS.addListener(::onMouseButton)
        // Channel Link Tool inline pin window — hover state + HUD draw.
        FORGE_BUS.addListener<net.neoforged.neoforge.client.event.RenderGuiEvent.Post>(LinkHudRenderer::onRenderGui)
        // AR Glasses: render video FBO under the vanilla HUD.
        FORGE_BUS.addListener<net.neoforged.neoforge.client.event.RenderGuiEvent.Pre>(ArClientState::onRenderGuiPre)
        // Control Block piloting: suppress vanilla movement/interaction + HUD.
        FORGE_BUS.addListener(::onMovementInput)
        FORGE_BUS.addListener(::onInteractionKey)
        // Panel joystick hold session: RMB starts it, HUD overlay while active.
        FORGE_BUS.addListener(::onPanelJoystickRightClick)
        FORGE_BUS.addListener<net.neoforged.neoforge.client.event.RenderGuiEvent.Post> {
            dev.nitka.nodewire.client.panel.PanelJoystickSession.renderHud(it.guiGraphics)
        }
        FORGE_BUS.addListener<net.neoforged.neoforge.client.event.RenderGuiEvent.Post>(
            dev.nitka.nodewire.client.control.ControlHud::onRenderGui,
        )
        LOG.info("Nodewire client handlers registered (MOD bus + FORGE bus)")
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        // Video handle GC sweep — runs every client tick regardless of whether a
        // screen is open (the early-return below only gates the dev keybind).
        VideoManager.onClientTick()
        // AR Glasses: resize surface to GUI scale, detect wear state changes.
        ArClientState.onClientTick(event)
        // Refresh the Link Tool hover window from the crosshair (no-ops / clears
        // itself when the tool isn't held or a screen is open).
        LinkHud.update()
        // Pillar 2 Stage A — publish far-camera chunk zones + request streaming
        // (no-ops without far cameras; sends only on change).
        dev.nitka.nodewire.camerachunk.CameraChunkClient.tick()
        dev.nitka.nodewire.client.command.AimCommand.tick()
        dev.nitka.nodewire.client.camera.CameraGizmoSession.tick()
        // Stream the pilot's input while a Control Block session is active.
        ControlSession.update()
        // Panel joystick hold session: liveness + state streaming.
        dev.nitka.nodewire.client.panel.PanelJoystickSession.clientTick(Minecraft.getInstance())
        // Drain the mouse-capture keybind; toggle only while piloting (Control
        // Block session or a persistent panel joystick session).
        var toggled = false
        while (CONTROL_MOUSE_KEY.consumeClick()) toggled = true
        if (toggled && ControlSession.isActive()) ControlSession.toggleMouse()
        // Dedicated exit key (RMB can't exit — it's suppressed while piloting).
        var exitPressed = false
        while (CONTROL_EXIT_KEY.consumeClick()) exitPressed = true
        if (exitPressed && ControlSession.isActive()) {
            ControlSession.exit()
            Minecraft.getInstance().player?.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Exited control")
                    .withStyle(net.minecraft.ChatFormatting.AQUA),
                true,
            )
        }
        // Keep the HUD's key hints in sync with the (rebindable) keybinds —
        // English labels (KeyNames) regardless of the game language.
        dev.nitka.nodewire.client.control.ControlHud.exitKeyName =
            dev.nitka.nodewire.block.control.KeyNames.label(CONTROL_EXIT_KEY.key.value)
        dev.nitka.nodewire.client.control.ControlHud.mouseKeyName =
            dev.nitka.nodewire.block.control.KeyNames.label(CONTROL_MOUSE_KEY.key.value)
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

    /**
     * Scroll with the Channel Link Tool in the MAIN hand:
     *  * **Sneak+scroll** → cycle the tool mode (LINK ↔ PANEL). Server mutates
     *    the stack NBT (a client-side write would desync).
     *  * **Plain scroll** while the inline pin window has selectable pins →
     *    move the highlight ([LinkHud]). Both cancel the event so the hotbar
     *    doesn't also scroll; otherwise the event falls through to the hotbar.
     */
    private fun onMouseScroll(event: net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent) {
        val player = Minecraft.getInstance().player ?: return
        // Piloting + mouse captured → the wheel feeds the Control Block's SCROLL
        // bindings instead of switching the hotbar.
        if (ControlSession.isActive() && ControlSession.mouseCaptured) {
            val dy = event.scrollDeltaY
            if (dy != 0.0) {
                ControlSession.addScroll(dy)
                event.isCanceled = true
            }
            return
        }
        if (player.mainHandItem.item !is dev.nitka.nodewire.item.ChannelLinkToolItem) return
        val dy = event.scrollDeltaY
        if (dy == 0.0) return
        if (player.isShiftKeyDown) {
            event.isCanceled = true
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                dev.nitka.nodewire.net.SetLinkToolModePacket(if (dy > 0) 1 else -1),
            )
            return
        }
        if (LinkHud.hasActive()) {
            event.isCanceled = true
            LinkHud.scroll(if (dy > 0) -1 else 1)
        }
    }

    /**
     * Middle mouse button (the rebindable Pick Block control) with the Channel
     * Link Tool while the inline window highlights a BOUND input pin → unbind
     * it. The pin selection is the same client-side [LinkHud] state the RMB
     * arm/commit flow uses; the server validates reach. Cancels the event so
     * vanilla pick-block doesn't also fire.
     */
    /**
     * RMB on a panel JOYSTICK element starts the client HOLD session (the
     * Dashpanels interaction model). The event is cancelled so the click never
     * reaches the server as an operate — all state flows through
     * [dev.nitka.nodewire.net.PanelJoystickPacket]. While a session is live,
     * the use-key auto-repeat is swallowed too.
     */
    private fun onPanelJoystickRightClick(event: net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock) {
        val level = event.level
        if (!level.isClientSide) return
        val session = dev.nitka.nodewire.client.panel.PanelJoystickSession
        if (session.isActive()) {
            // The hold session owns the mouse: swallow use-key repeats.
            event.isCanceled = true
            return
        }
        val state = level.getBlockState(event.pos)
        if (state.block !is dev.nitka.nodewire.block.ControlPanelBlock) return
        val item = event.itemStack.item
        if (item is dev.nitka.nodewire.item.PanelElementItem ||
            item is dev.nitka.nodewire.item.PanelKeyItem ||
            item is dev.nitka.nodewire.item.ChannelLinkToolItem
        ) {
            return
        }
        val gh = dev.nitka.nodewire.block.ControlPanelBlock.gridHit(state, event.pos, event.hitVec.location)
            ?: return
        val be = level.getBlockEntity(event.pos) as? dev.nitka.nodewire.block.ControlPanelBlockEntity ?: return
        val el = be.elementAt(gh.cell) ?: return
        // RightClickBlock fires once per HAND — act on MAIN_HAND only, but
        // still swallow the offhand pass so it can't re-toggle the session
        // or use the held item against the panel.
        if (event.hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
            if (el.typeId == "joystick" || el.typeId == "joystick_ctrl") event.isCanceled = true
            return
        }
        when (el.typeId) {
            "joystick" -> {
                if (event.entity.isShiftKeyDown) return
                session.start(event.pos, el.pinId())
                event.isCanceled = true
            }
            // Embedded Control Block: sneak+RMB = binding editor, RMB = the
            // exact Control Block piloting toggle (same session, keybinds, HUD).
            "joystick_ctrl" -> {
                event.isCanceled = true
                if (event.entity.isShiftKeyDown) {
                    dev.nitka.nodewire.client.screen.ControlConfigScreen.open(
                        event.pos,
                        dev.nitka.nodewire.block.panel.PanelControlBindings.of(el.config),
                        el.pinId(),
                    )
                } else {
                    ControlSession.toggle(event.pos, el.pinId())
                    val on = ControlSession.isActive()
                    val exitKey = dev.nitka.nodewire.client.control.ControlHud.exitKeyName
                    Minecraft.getInstance().player?.displayClientMessage(
                        net.minecraft.network.chat.Component
                            .literal(if (on) "Controlling — press $exitKey to exit" else "Exited control")
                            .withStyle(net.minecraft.ChatFormatting.AQUA),
                        true,
                    )
                }
            }
        }
    }

    private fun onMouseButton(event: net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre) {
        // Panel joystick session: LMB is the TRIGGER (press = down, release =
        // up), swallowed so it never breaks the panel underneath. Clicks are
        // ONLY captured while the session's mouse capture is on — with capture
        // off (ctrl variant, V) the mouse behaves normally.
        if (dev.nitka.nodewire.client.camera.CameraGizmoSession.isActive()
            && event.button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            && event.action == org.lwjgl.glfw.GLFW.GLFW_RELEASE
        ) {
            dev.nitka.nodewire.client.camera.CameraGizmoSession.endDrag()
            event.isCanceled = true
            return
        }
        if (dev.nitka.nodewire.client.panel.PanelJoystickSession.isActive()
            && event.button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
        ) {
            dev.nitka.nodewire.client.panel.PanelJoystickSession
                .setTrigger(event.action != org.lwjgl.glfw.GLFW.GLFW_RELEASE)
            event.isCanceled = true
            return
        }
        if (event.action != org.lwjgl.glfw.GLFW.GLFW_PRESS) return
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return
        val player = mc.player ?: return
        val stack = player.mainHandItem
        if (stack.item !is dev.nitka.nodewire.item.ChannelLinkToolItem) return
        if (dev.nitka.nodewire.item.ChannelLinkToolItem.readMode(stack)
            != dev.nitka.nodewire.item.ChannelLinkToolItem.Mode.LINK
        ) return
        if (!mc.options.keyPickItem.matchesMouse(event.button)) return
        val link = LinkHud.highlightedLink() ?: return
        val sink = LinkHud.targetPos ?: return
        event.isCanceled = true
        val sideFeed = LinkHud.highlightedSideFeed()
        if (sideFeed != null) {
            // Sided-redstone feed: the binding lives on the SOURCE logic block,
            // so removal is addressed there (same packet the source-side HUD uses).
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                dev.nitka.nodewire.net.RemoveBindingPacket(
                    sideFeed.sourcePos,
                    sideFeed.binding.sourceChannelName,
                    sideFeed.binding.target,
                    dev.nitka.nodewire.net.RemoveBindingPacket.Kind.SIDE,
                    sideFeed.binding.targetSide.name,
                ),
            )
        } else {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                dev.nitka.nodewire.net.RemovePinLinkPacket(sink, link.source, link.sourcePin, link.targetPin),
            )
        }
        player.displayClientMessage(
            net.minecraft.network.chat.Component.literal("Unlinked ${link.targetPin}")
                .withStyle(net.minecraft.ChatFormatting.AQUA),
            true,
        )
    }

    /**
     * While piloting a Control Block, zero the player's movement input so WASD /
     * jump / sneak drive only the block's pins (read raw) and don't walk the
     * player out of the seat. Mouse-look is untouched so look-to-aim still works.
     */
    private fun onMovementInput(event: net.neoforged.neoforge.client.event.MovementInputUpdateEvent) {
        if (!ControlSession.isActive()) return
        val i = event.input
        i.forwardImpulse = 0f
        i.leftImpulse = 0f
        i.up = false
        i.down = false
        i.left = false
        i.right = false
        i.jumping = false
        i.shiftKeyDown = false
    }

    /** While piloting, cancel attack / use / pick so LMB/RMB feed the pins
     *  instead of breaking, placing or picking blocks. */
    private var lastSuppressLog = 0L
    private var lastElementRemoval = 0L

    /**
     * Send a removal request for the panel element under the crosshair, if
     * there is one. Returns true when the click was consumed.
     */
    private fun removePanelElementUnderCrosshair(): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return false
        val player = mc.player ?: return false
        if (player.isSpectator) return false
        // Only bare-handed: with an element item in hand the click should
        // still mine, and the Panel Key has its own removal.
        if (!player.mainHandItem.isEmpty) return false
        val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult ?: return false
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return false
        val level = mc.level ?: return false
        val state = level.getBlockState(hit.blockPos)
        if (state.block !is dev.nitka.nodewire.block.ControlPanelBlock) return false
        val gh = dev.nitka.nodewire.block.ControlPanelBlock.gridHit(state, hit.blockPos, hit.location)
            ?: return false
        val be = level.getBlockEntity(hit.blockPos) as? dev.nitka.nodewire.block.ControlPanelBlockEntity
            ?: return false
        be.elementAt(gh.cell) ?: return false // bare plate → let vanilla mine the panel

        val now = System.currentTimeMillis()
        if (now - lastElementRemoval < 250L) return true // held button: consume, don't repeat
        lastElementRemoval = now
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            dev.nitka.nodewire.net.RemoveElementPacket(hit.blockPos, gh.cell.x, gh.cell.y),
        )
        return true
    }

    private fun onInteractionKey(
        event: net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered,
    ) {
        // Gizmo editor: the attack key grabs the handle under the crosshair,
        // and use/pick are swallowed so a drag never places or breaks anything.
        if (dev.nitka.nodewire.client.camera.CameraGizmoSession.isActive()) {
            if (event.isAttack && dev.nitka.nodewire.client.camera.CameraGizmoSession.beginDrag()) {
                event.isCanceled = true
                event.setSwingHand(false)
                return
            }
            if (event.isAttack || event.isUseItem || event.isPickBlock) {
                event.isCanceled = true
                event.setSwingHand(false)
                return
            }
        }

        // Panel elements pop off with a bare-handed attack. This has to happen
        // at the key press: vanilla's own attack path silently declines to
        // start breaking our panel (no LeftClickBlock event is ever fired for
        // it, verified with /nodewire aim showing the crosshair squarely on
        // the block), so a handler further down the chain never runs.
        if (event.isAttack && removePanelElementUnderCrosshair()) {
            event.isCanceled = true
            event.setSwingHand(false)
            return
        }

        // Clicks belong to the session ONLY while its mouse capture is on —
        // with capture off the pilot interacts with the world normally.
        val controlCaptured = ControlSession.isActive() && ControlSession.mouseCaptured
        val joystickCaptured = dev.nitka.nodewire.client.panel.PanelJoystickSession.isActive()
        if (!controlCaptured && !joystickCaptured) return
        // A swallowed click is indistinguishable from a broken feature, and a
        // session that failed to end would eat every click for the rest of the
        // session — say so, at most once a second.
        val now = System.currentTimeMillis()
        if (now - lastSuppressLog > 1000L) {
            lastSuppressLog = now
            LOG.info(
                "clicks routed to a control session (control={}, joystick={}) — Shift exits, V frees the mouse",
                controlCaptured, joystickCaptured,
            )
        }
        event.isCanceled = true
        event.setSwingHand(false)
    }
}
