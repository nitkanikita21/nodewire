package dev.nitka.nodewire.client.panel

import dev.nitka.nodewire.block.panel.PanelSpace
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Client-side float view over [PanelSpace] — the shared grid → surface mapping
 * used by the renderer, kept in lock-step with the block's raycast shape and
 * hit-testing because they all delegate to the same common math.
 */
object PanelSurface {
    /** Display plane distance from the −FACE (mount) wall — nearly flush. */
    const val FACE_GAP = PanelSpace.FACE_GAP

    /** Block-local 3D point for grid `(u,v)` on [face] with [spin], at [outset]. */
    fun local(u: Double, v: Double, face: Direction, spin: Int, outset: Double): FloatArray {
        val p = PanelSpace.local(u, v, face, spin, outset)
        return floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }

    /** World-space point for grid `(u,v)` on the panel at [pos]. */
    fun world(pos: BlockPos, u: Double, v: Double, face: Direction, spin: Int, outset: Double): Vec3 {
        val l = PanelSpace.local(u, v, face, spin, outset)
        return Vec3(pos.x + l[0], pos.y + l[1], pos.z + l[2])
    }
}
