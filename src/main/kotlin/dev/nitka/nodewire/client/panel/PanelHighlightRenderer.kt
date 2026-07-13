package dev.nitka.nodewire.client.panel

import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.panel.PanelSpace
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.neoforge.client.event.RenderHighlightEvent
import kotlin.math.sqrt

/**
 * Replaces the vanilla block-selection outline on a Control Panel: instead of
 * boxing the whole shape (plate + every element), only the ELEMENT under the
 * crosshair gets the outline — same vanilla effect (dark translucent lines),
 * scoped to what you are actually pointing at. With no element under the
 * cursor the bare plate slab is outlined.
 */
object PanelHighlightRenderer {

    fun onHighlight(event: RenderHighlightEvent.Block) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val hit = event.target
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.block !is ControlPanelBlock) return
        event.isCanceled = true // suppress the whole-shape vanilla outline

        val face = state.getValue(ControlPanelBlock.FACE)
        val spin = state.getValue(ControlPanelBlock.SPIN)
        val be = level.getBlockEntity(pos) as? ControlPanelBlockEntity
        val el = ControlPanelBlock.gridHit(state, pos, hit.location)?.let { be?.elementAt(it.cell) }
        val shape: VoxelShape =
            if (el != null) Shapes.create(PanelSpace.elementBox(el, face, spin))
            else ControlPanelBlock.plateShape(face)

        val cam = event.camera.position
        val px = pos.x - cam.x
        val py = pos.y - cam.y
        val pz = pos.z - cam.z
        val entry = event.poseStack.last()
        val consumer = event.multiBufferSource.getBuffer(RenderType.lines())
        // Vanilla renderShape recipe: one line per shape edge, colour black 40%,
        // normal = the edge direction (the lines shader fades by it).
        shape.forAllEdges { x0, y0, z0, x1, y1, z1 ->
            var dx = (x1 - x0).toFloat()
            var dy = (y1 - y0).toFloat()
            var dz = (z1 - z0).toFloat()
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len > 1.0e-6f) {
                dx /= len; dy /= len; dz /= len
                consumer.addVertex(entry.pose(), (x0 + px).toFloat(), (y0 + py).toFloat(), (z0 + pz).toFloat())
                    .setColor(0f, 0f, 0f, 0.4f)
                    .setNormal(entry, dx, dy, dz)
                consumer.addVertex(entry.pose(), (x1 + px).toFloat(), (y1 + py).toFloat(), (z1 + pz).toFloat())
                    .setColor(0f, 0f, 0f, 0.4f)
                    .setNormal(entry, dx, dy, dz)
            }
        }
    }
}
