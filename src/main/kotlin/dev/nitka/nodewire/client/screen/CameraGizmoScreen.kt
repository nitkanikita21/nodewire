package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.client.camera.CameraGizmoSession
import dev.nitka.nodewire.client.camera.CameraGizmoView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * The camera gizmo editor: an orbit view around the lens with draggable
 * handles, in the shape Synaxis' weld gizmo uses.
 *
 * Its gizmo could not be reused — the only way in is a weld session between
 * two physics bodies, with every drag expressed as a body pose — so this is
 * the same interaction rebuilt over our own values: left-drag a handle to move
 * or aim, left-drag empty space to pan, right-drag to orbit, wheel to zoom,
 * Shift for the fine step. One mode is shown at a time, as there.
 *
 * The world keeps rendering behind the screen, which is the point: the view
 * ray shows the aim while it is being changed.
 */
class CameraGizmoScreen(private val pos: BlockPos) : Screen(Component.literal("Camera Gizmo")) {

    private enum class Drag { NONE, HANDLE, PAN, ORBIT }

    private var drag = Drag.NONE

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        if (!CameraGizmoSession.isActive()) {
            if (!CameraGizmoSession.open(pos)) {
                onClose()
                return
            }
            CameraGizmoSession.eyeWorld()?.let { CameraGizmoView.reset(it) }
        }
        CameraGizmoSession.viewControlled = true

        var x = 8
        val y = height - 26
        addRenderableWidget(
            Button.builder(Component.literal("Move")) {
                CameraGizmoSession.mode = CameraGizmoSession.Mode.MOVE
            }.bounds(x, y, 54, 20).build(),
        )
        x += 58
        addRenderableWidget(
            Button.builder(Component.literal("Rotate")) {
                CameraGizmoSession.mode = CameraGizmoSession.Mode.ROTATE
            }.bounds(x, y, 54, 20).build(),
        )
        x += 58
        addRenderableWidget(
            Button.builder(Component.literal("Reset")) {
                CameraGizmoSession.reset()
            }.bounds(x, y, 54, 20).build(),
        )
        x += 58
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(x, y, 54, 20).build(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // No dimming: the world behind IS the editor.
        super.render(graphics, mouseX, mouseY, partialTick)

        val mode = if (CameraGizmoSession.mode == CameraGizmoSession.Mode.MOVE) "Move" else "Rotate"
        val lines = listOf(
            "Camera gizmo — $mode",
            "right %.2f   up %.2f   forward %.2f".format(
                CameraGizmoSession.right, CameraGizmoSession.up, CameraGizmoSession.forward,
            ),
            "yaw %.1f°   pitch %.1f°".format(CameraGizmoSession.yaw, CameraGizmoSession.pitch),
            "LMB handle · LMB drag pan · RMB orbit · wheel zoom · Shift fine",
        )
        var y = 8
        for (line in lines) {
            graphics.drawString(font, line, 8, y, 0xFFE7EDF5.toInt(), true)
            y += 11
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        when (button) {
            GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                val origin = CameraGizmoView.position()
                val ray = CameraGizmoView.rayAt(mouseX, mouseY)
                // A click that misses every handle pans the view instead, so
                // the mouse is never "wasted" on empty space.
                drag = if (CameraGizmoSession.beginDragAt(origin, ray)) Drag.HANDLE else Drag.PAN
                return true
            }
            GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                drag = Drag.ORBIT
                return true
            }
        }
        return false
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        when (drag) {
            Drag.HANDLE -> CameraGizmoSession.update(
                CameraGizmoView.position(),
                CameraGizmoView.rayAt(mouseX, mouseY),
                hasShiftDown(),
            )
            Drag.PAN -> CameraGizmoView.pan(dragX, dragY)
            Drag.ORBIT -> CameraGizmoView.orbit(dragX, dragY)
            Drag.NONE -> return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        }
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (drag == Drag.HANDLE) CameraGizmoSession.endDrag()
        drag = Drag.NONE
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        CameraGizmoView.zoom(scrollY)
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (drag == Drag.NONE) {
            CameraGizmoSession.update(
                CameraGizmoView.position(),
                CameraGizmoView.rayAt(mouseX, mouseY),
                hasShiftDown(),
            )
        }
    }

    override fun onClose() {
        CameraGizmoSession.viewControlled = false
        CameraGizmoSession.close()
        super.onClose()
    }
}
