package dev.nitka.nodewire.block.panel

import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB

/**
 * COMMON-side grid → block-local geometry for the Control Panel: the single
 * source of truth shared by the client renderer (via `PanelSurface`), the
 * block's dynamic [net.minecraft.world.phys.shapes.VoxelShape] (raycast /
 * selection) and the hovered-element outline.
 *
 * Grid space: top-left origin on the display face, u right, v down (the exact
 * inverse of [PanelGrid.hitToGrid]); heights are outsets off the mounting wall.
 */
object PanelSpace {
    /** Display plane distance from the −FACE (mount) wall: one MC pixel, so
     *  the 1px-thick plate model (y −1..0 in 16ths) rests flush on the wall. */
    const val FACE_GAP = 0.0625

    /** Cell inset between an element body and its footprint (render + shapes). */
    const val ELEMENT_GAP = 0.06

    /** Block-local point for grid `(u,v)` on [face] with [spin], at [outset]. */
    fun local(u: Double, v: Double, face: Direction, spin: Int, outset: Double): DoubleArray {
        val (su, sv) = PanelGrid.applySpin(u, v, (4 - (spin % 4)) % 4)
        val near = FACE_GAP + outset
        val far = 1.0 - FACE_GAP - outset
        return when (face) {
            Direction.UP -> doubleArrayOf(su, near, sv)
            Direction.DOWN -> doubleArrayOf(su, far, 1 - sv)
            Direction.SOUTH -> doubleArrayOf(su, 1 - sv, near)
            Direction.NORTH -> doubleArrayOf(1 - su, 1 - sv, far)
            Direction.EAST -> doubleArrayOf(near, 1 - sv, 1 - su)
            Direction.WEST -> doubleArrayOf(far, 1 - sv, su)
        }
    }

    /** How far an element type's tallest part rises off the wall (matches the
     *  renderer's relief so the selection box hugs the visible body). */
    fun relief(typeId: String): Double = when {
        // Model max-Y (16ths, natural Dashpanels scale) / 16 + a small margin;
        // outsets are measured off the display plane ([FACE_GAP], plate front).
        typeId == "switch" -> 0.18
        typeId == "momentary" || typeId == "push_button" -> 0.10
        typeId == "key_switch" -> 0.225
        typeId in setOf("emergency", "lever", "knob") -> 0.13
        typeId == "joystick" || typeId == "joystick_ctrl" -> 0.32
        typeId == "bulb" -> 0.11
        typeId == "seven_segment" -> 0.07
        typeId == "buzzer" -> 0.075
        PanelElements.isScreen(typeId) -> 0.042
        typeId == "label" -> 0.012
        else -> 0.060
    }

    /**
     * Block-local AABB of a placed element's raised body (gap-inset footprint,
     * wall → [relief]). Axis-aligned for every FACE+SPIN (all transforms are
     * 90° steps), so min/max over the mapped corners is exact.
     */
    fun elementBox(e: PlacedElement, face: Direction, spin: Int): AABB {
        val g = ELEMENT_GAP
        val u0 = (e.cellX + g) / 16.0
        val v0 = (e.cellY + g) / 16.0
        val u1 = (e.cellX + e.cols - g) / 16.0
        val v1 = (e.cellY + e.rows - g) / 16.0
        val h = relief(e.typeId)
        val pts = listOf(
            local(u0, v0, face, spin, 0.0), local(u1, v1, face, spin, 0.0),
            local(u0, v0, face, spin, h), local(u1, v1, face, spin, h),
            local(u1, v0, face, spin, 0.0), local(u0, v1, face, spin, h),
        )
        return AABB(
            pts.minOf { it[0] }, pts.minOf { it[1] }, pts.minOf { it[2] },
            pts.maxOf { it[0] }, pts.maxOf { it[1] }, pts.maxOf { it[2] },
        )
    }
}
