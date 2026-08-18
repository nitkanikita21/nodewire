package dev.nitka.nodewire.client.camera

import dev.nitka.nodewire.item.CameraCableItem
import net.createmod.catnip.outliner.Outliner
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * Welding-style snap-point overlay for the armed Camera Cable (the Synaxis
 * weld-tool UX): aim at any block face and its 3×3 snap grid (corners / edge
 * midpoints / centre) lights up; the point nearest the crosshair renders
 * bigger and brighter — that exact point is where the second click drops the
 * Remote Camera's eye. Points are pushed through the live Sable pose, so the
 * grid sticks to moving hulls.
 *
 * Drawn through catnip's [Outliner] (the same Veil/Sodium-safe pass the wire
 * renderer uses); keys are diffed per frame so markers vanish the moment the
 * cable is put away.
 */
object CameraCableOverlay {

    private const val MARKER = 0.045
    private const val HOVER_MARKER = 0.10
    private const val GRID_COLOR = 0x66FFFFFF
    private const val HOVER_COLOR = 0xFF57C2FF.toInt()
    private const val WIDTH = 1.0f / 24f

    private val shownKeys = HashSet<Any>()

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val mc = Minecraft.getInstance()
        val outliner = Outliner.getInstance()
        val frameKeys = HashSet<Any>()

        collect(mc, outliner, frameKeys)

        val it = shownKeys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in frameKeys) { outliner.remove(k); it.remove() }
        }
        shownKeys.addAll(frameKeys)
    }

    private fun collect(mc: Minecraft, outliner: Outliner, frameKeys: MutableSet<Any>) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val stack = when {
            player.mainHandItem.item is CameraCableItem -> player.mainHandItem
            player.offhandItem.item is CameraCableItem -> player.offhandItem
            else -> return
        }
        if (CameraCableItem.armedPos(stack) == null) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return

        val pos = hit.blockPos
        val face = hit.direction
        // Which point is nearest is decided in BLOCK-LOCAL space: the raycast
        // reports plot coordinates on a Sable sub-level while the drawn points
        // are at the ship's rendered position, so comparing the two spaces let
        // one point win every time and the grid never appeared to react.
        val local = CameraCableItem.snapPointsLocal(face)
        val localHit = hit.location.subtract(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        val nearestIndex = local.indices.minByOrNull { local[it].distanceToSqr(localHit) }
        val points = local.map { CameraCableItem.localToWorld(level, pos, it) }

        for ((i, p) in points.withIndex()) {
            val hovered = i == nearestIndex
            val r = if (hovered) HOVER_MARKER else MARKER
            val color = if (hovered) HOVER_COLOR else GRID_COLOR
            cross(outliner, frameKeys, "nw:cable:$i", p, r, color)
        }
    }

    /** Tiny 3-axis cross marker at [p] — cheaper than a 12-edge box. */
    private fun cross(outliner: Outliner, frameKeys: MutableSet<Any>, key: String, p: Vec3, r: Double, color: Int) {
        line(outliner, frameKeys, "$key:x", p.add(-r, 0.0, 0.0), p.add(r, 0.0, 0.0), color)
        line(outliner, frameKeys, "$key:y", p.add(0.0, -r, 0.0), p.add(0.0, r, 0.0), color)
        line(outliner, frameKeys, "$key:z", p.add(0.0, 0.0, -r), p.add(0.0, 0.0, r), color)
    }

    private fun line(outliner: Outliner, frameKeys: MutableSet<Any>, key: String, a: Vec3, b: Vec3, color: Int) {
        outliner.showLine(key, a, b).lineWidth(WIDTH).colored(color).disableLineNormals().disableCull()
        frameKeys.add(key)
    }
}
