package dev.nitka.nodewire.client.control

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.block.control.BindKind
import dev.nitka.nodewire.block.control.Binding
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.net.ControlInputPacket
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * Client-side piloting session for a Control Block. While [active], every
 * client tick reads the bound keys/mouse-buttons, computes each [Binding]'s pin
 * value from the block's synced layout and streams them to the server
 * ([ControlInputPacket]) for the BE to serve to linked consumers.
 *
 * Toggled by right-clicking the block ([toggle]); auto-exits if the player gets
 * too far away or the block is gone. Capture pauses while a GUI is open so the
 * pilot can use menus without "ghost" input.
 */
object ControlSession {

    private const val EXIT_DIST_SQ = 8.0 * 8.0

    var active: BlockPos? = null
        private set

    /** Mouse aiming on/off (toggled by the keybind); only meaningful in a session. */
    var mouseCaptured: Boolean = false
        private set

    /** Wheel delta accumulated since the last sent tick (only while captured). */
    private var scrollAccum: Double = 0.0

    /** Virtual look the mouse drives while captured — the head stays FROZEN
     *  (NodewireClient zeroes the turn sensitivity), so the mouse aims without
     *  moving the player's view. Holds its last value when capture is off. */
    var lookYaw: Float = 0f
        private set
    var lookPitch: Float = 0f
        private set

    /** Per-tick mouse movement (scaled degrees) for MOUSE_DELTA bindings —
     *  accumulated by [addLookDelta], emitted then reset each sent tick. */
    private var deltaX: Double = 0.0
    private var deltaY: Double = 0.0

    fun isActive(): Boolean = active != null

    /** Right-click a block: enter its session, or exit if already piloting it. */
    fun toggle(pos: BlockPos) {
        active = if (active == pos) null else pos
        mouseCaptured = false
        scrollAccum = 0.0
    }

    fun exit() {
        active = null
        mouseCaptured = false
        scrollAccum = 0.0
    }

    /** Keybind: toggle mouse aiming (the "capture") within the active session. */
    fun toggleMouse() {
        if (!isActive()) return
        mouseCaptured = !mouseCaptured
        if (mouseCaptured) {
            // Seed the virtual look from the current head direction so the
            // turret starts aligned before the head is frozen.
            Minecraft.getInstance().player?.let { lookYaw = it.yRot; lookPitch = it.xRot }
        }
    }

    /** Feed wheel deltas while aiming (from the scroll event). */
    fun addScroll(delta: Double) {
        if (isActive() && mouseCaptured) scrollAccum += delta
    }

    /** One capture tick. No-op unless a session is active and unobstructed. */
    fun update() {
        val pos = active ?: return
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return // don't capture while a GUI is open
        val player = mc.player ?: return exit()
        val level = mc.level ?: return exit()
        val be = level.getBlockEntity(pos) as? ControlBlockEntity ?: return exit()
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > EXIT_DIST_SQ) return exit()

        val window = mc.window.window
        val values = HashMap<String, PinValue>(be.bindings().size + 2)
        for (b in be.bindings()) values[b.pin] = compute(b, window)
        values[ControlBlockEntity.ACTIVE_PIN] = PinValue.Bool(true)
        values[ControlBlockEntity.MOUSE_CAPTURED_PIN] = PinValue.Bool(mouseCaptured)
        scrollAccum = 0.0 // consumed this tick
        deltaX = 0.0
        deltaY = 0.0
        PacketDistributor.sendToServer(ControlInputPacket(pos, values))
    }

    private fun down(window: Long, key: Int): Boolean {
        if (key < 0) return false
        return if (key >= Binding.MOUSE_BUTTON_BASE) {
            // Mouse buttons feed pins ONLY while the mouse is captured — with
            // capture off, clicks are the player's (world interaction), not the
            // vehicle's. Keyboard bindings keep working either way.
            mouseCaptured &&
                GLFW.glfwGetMouseButton(window, key - Binding.MOUSE_BUTTON_BASE) == GLFW.GLFW_PRESS
        } else {
            InputConstants.isKeyDown(window, key)
        }
    }

    private fun compute(b: Binding, window: Long): PinValue {
        fun key(i: Int): Int = b.keys.getOrElse(i) { -1 }
        // Axis from a (negative, positive) key pair → -1 / 0 / +1.
        fun axis(neg: Int, pos: Int): Float =
            (if (down(window, pos)) 1f else 0f) - (if (down(window, neg)) 1f else 0f)

        return when (b.kind) {
            BindKind.BUTTON -> PinValue.Bool(down(window, key(0)))
            BindKind.AXIS -> PinValue.Float(axis(key(0), key(1)))
            BindKind.VECTOR -> {
                // keys = [forward, back, left, right]
                val y = axis(key(1), key(0)) // pos=forward(0), neg=back(1) → forward − back
                val x = axis(key(2), key(3)) // pos=right(3), neg=left(2) → right − left
                PinValue.Vec2(x.toDouble(), y.toDouble())
            }
            // The mouse-driven virtual look (x = yaw, y = pitch, degrees). Holds
            // its last value when capture is off; the graph gates on
            // `mouse_captured` (e.g. sample-and-hold on release).
            BindKind.MOUSE_LOOK -> PinValue.Vec2(lookYaw.toDouble(), lookPitch.toDouble())
            // Mouse movement since the last tick (scaled degrees); 0 when idle
            // or capture off.
            BindKind.MOUSE_DELTA -> PinValue.Vec2(deltaX, deltaY)
            BindKind.SCROLL -> PinValue.Float(if (mouseCaptured) scrollAccum.toFloat() else 0f)
        }
    }

    /**
     * Fold a raw mouse delta into the virtual aim look. Called from
     * [dev.nitka.nodewire.mixin.control.MixinMouseHandler] each frame while the
     * head is frozen, so the mouse aims without moving the player's view.
     */
    fun addLookDelta(dx: Double, dy: Double) {
        if (!isActive() || !mouseCaptured) return
        val sens = sensitivity()
        val sx = dx * sens
        val sy = dy * sens
        lookYaw = wrap180(lookYaw + sx.toFloat())
        lookPitch = (lookPitch + sy.toFloat()).coerceIn(-90f, 90f)
        deltaX += sx
        deltaY += sy
    }

    /** Degrees-per-pixel, scaled to roughly match the player's mouse sensitivity. */
    private fun sensitivity(): Double {
        val s = Minecraft.getInstance().options.sensitivity().get()
        val f = s * 0.6 + 0.2
        return f * f * f * 8.0 * 0.15
    }

    private fun wrap180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
