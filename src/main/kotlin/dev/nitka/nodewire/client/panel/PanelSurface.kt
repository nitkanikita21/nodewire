package dev.nitka.nodewire.client.panel

import dev.nitka.nodewire.block.panel.PanelGrid
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Shared grid → world-surface mapping for the Control Panel, used by both the
 * renderer and the in-world placement preview so they stay in lock-step.
 *
 * Maps grid `(u,v)` (top-left origin, u right, v down) onto the panel's display
 * plane — the exact inverse of [PanelGrid.hitToGrid] (un-spin, then face-uv⁻¹).
 * The plane sits [FACE_GAP] off the mounting wall (nearly flush), plus a small
 * per-layer [outset] so stacked quads / overlays don't z-fight.
 */
object PanelSurface {
    /** Display plane distance from the −FACE (mount) wall — nearly flush. */
    const val FACE_GAP = 0.015

    /** Block-local 3D point for grid `(u,v)` on [face] with [spin], at [outset]. */
    fun local(u: Double, v: Double, face: Direction, spin: Int, outset: Double): FloatArray {
        val (su, sv) = PanelGrid.applySpin(u, v, (4 - (spin % 4)) % 4)
        val near = (FACE_GAP + outset).toFloat()
        val far = (1.0 - FACE_GAP - outset).toFloat()
        return when (face) {
            Direction.UP -> floatArrayOf(su.toFloat(), near, sv.toFloat())
            Direction.DOWN -> floatArrayOf(su.toFloat(), far, (1 - sv).toFloat())
            Direction.SOUTH -> floatArrayOf(su.toFloat(), (1 - sv).toFloat(), near)
            Direction.NORTH -> floatArrayOf((1 - su).toFloat(), (1 - sv).toFloat(), far)
            Direction.EAST -> floatArrayOf(near, (1 - sv).toFloat(), (1 - su).toFloat())
            Direction.WEST -> floatArrayOf(far, (1 - sv).toFloat(), su.toFloat())
        }
    }

    /** World-space point for grid `(u,v)` on the panel at [pos]. */
    fun world(pos: BlockPos, u: Double, v: Double, face: Direction, spin: Int, outset: Double): Vec3 {
        val l = local(u, v, face, spin, outset)
        return Vec3(pos.x + l[0].toDouble(), pos.y + l[1].toDouble(), pos.z + l[2].toDouble())
    }
}
