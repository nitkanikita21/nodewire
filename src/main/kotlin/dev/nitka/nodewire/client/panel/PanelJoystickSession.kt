package dev.nitka.nodewire.client.panel

import dev.nitka.nodewire.net.PanelJoystickPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side HOLD session for the `joystick` panel element — the Dashpanels
 * interaction model: RMB starts it and must stay held; the camera freezes
 * ([dev.nitka.nodewire.mixin.control.MixinMouseHandler]), mouse deltas
 * accumulate into a −1..1 stick, LMB holds the trigger; releasing RMB springs
 * everything back.
 *
 * (The `joystick_ctrl` element does NOT use this — it's a full embedded
 * Control Block driven by [dev.nitka.nodewire.client.control.ControlSession].)
 *
 * State streams to the server via [PanelJoystickPacket] (on change + a
 * keep-alive); the server-side expiry springs the stick back if the stream
 * just stops (disconnect, lag-out).
 */
object PanelJoystickSession {

    private var pos: BlockPos? = null
    private var pinId: String = ""
    private var valX = 0f
    private var valY = 0f
    private var triggered = false
    private var lastSent: Triple<Float, Float, Boolean>? = null
    private var sendCooldown = 0

    /** Raw mouse degrees → full deflection over 180° of turn (Dashpanels' map). */
    private const val SENS = 0.15f / 180f

    fun isActive(): Boolean = pos != null

    /** Begin a session for the joystick element [pinId] on the panel at [pos]. */
    fun start(pos: BlockPos, pinId: String) {
        this.pos = pos
        this.pinId = pinId
        valX = 0f
        valY = 0f
        triggered = false
        lastSent = null
        sendCooldown = 0
    }

    /** Raw accumulated mouse delta from the MouseHandler mixin. */
    fun addLookDelta(dx: Double, dy: Double) {
        if (!isActive()) return
        valX = Mth.clamp(valX + dx.toFloat() * SENS, -1f, 1f)
        valY = Mth.clamp(valY - dy.toFloat() * SENS, -1f, 1f)
    }

    /** LMB press/release while the session is active = trigger down/up. */
    fun setTrigger(down: Boolean) {
        if (isActive()) triggered = down
    }

    /** Once per client tick: liveness checks + change/keep-alive streaming. */
    fun clientTick(mc: Minecraft) {
        val p = pos ?: return
        val player = mc.player
        if (player == null || mc.screen != null || !mc.options.keyUse.isDown()
            || player.distanceToSqr(Vec3.atCenterOf(p)) > 64.0
        ) {
            stop()
            return
        }
        sendCooldown--
        val cur = Triple(valX, valY, triggered)
        if (cur != lastSent || sendCooldown <= 0) {
            PacketDistributor.sendToServer(PanelJoystickPacket(p, pinId, valX, valY, triggered))
            lastSent = cur
            sendCooldown = KEEPALIVE_TICKS
        }
    }

    /** End the session: zero the stick server-side and release the camera. */
    fun stop() {
        val p = pos ?: return
        PacketDistributor.sendToServer(PanelJoystickPacket(p, pinId, 0f, 0f, false))
        pos = null
        valX = 0f
        valY = 0f
        triggered = false
        lastSent = null
    }

    /** HUD: 21×21 crosshair box + 3×3 indicator + X/Y readout (Dashpanels look,
     *  drawn with plain fills — no textures to keep the overlay self-contained). */
    fun renderHud(graphics: GuiGraphics) {
        if (!isActive()) return
        val cx = graphics.guiWidth() / 2
        val cy = graphics.guiHeight() / 2
        val left = cx - 11
        val top = cy - 10
        val frame = 0x66FFFFFF
        // crosshair frame
        graphics.fill(left, top, left + 21, top + 1, frame)
        graphics.fill(left, top + 20, left + 21, top + 21, frame)
        graphics.fill(left, top + 1, left + 1, top + 20, frame)
        graphics.fill(left + 20, top + 1, left + 21, top + 20, frame)
        // centre tick
        graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0x44FFFFFF)
        // indicator
        val ix = Mth.map(valX, -1f, 1f, (left - 1).toFloat(), (left + 19).toFloat()).toInt()
        val iy = Mth.map(-valY, -1f, 1f, (top - 1).toFloat(), (top + 19).toFloat()).toInt()
        val color = if (triggered) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
        graphics.fill(ix, iy, ix + 3, iy + 3, color)
        // readout
        val font = Minecraft.getInstance().font
        graphics.pose().pushPose()
        graphics.pose().translate(cx.toDouble(), (cy + 16).toDouble(), 0.0)
        graphics.pose().scale(0.5f, 0.5f, 0.5f)
        graphics.drawCenteredString(font, "X: %.2f".format(valX), 0, 0, 0xAAFFCCCC.toInt())
        graphics.pose().translate(0.0, 12.0, 0.0)
        graphics.drawCenteredString(font, "Y: %.2f".format(valY), 0, 0, 0xAACCFFCC.toInt())
        graphics.pose().popPose()
    }

    private const val KEEPALIVE_TICKS = 4
}
