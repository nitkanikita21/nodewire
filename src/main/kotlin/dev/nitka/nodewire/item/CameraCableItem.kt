package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Camera Cable — binds a Remote Camera's displaced eye:
 *
 *  1. RMB a Remote Camera → arm the cable;
 *  2. RMB the block face where the VIEWPOINT should sit (a muzzle, a mast tip)
 *     → the eye lands just off that face and the tuning screen opens for the
 *     rotation + fine offsets (sliders + numeric fields).
 *
 * Sneak+RMB the camera opens the tuning screen for an existing eye;
 * sneak+RMB anywhere else disarms. The displacement is stored FACING-RELATIVE
 * (right/up/forward of the camera block), so on a Sable hull it rotates with
 * the vehicle for free.
 */
class CameraCableItem(props: Properties) : Item(props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS
        val stack = context.itemInHand
        val block = level.getBlockState(pos).block

        // 1) Clicking the Remote Camera itself.
        if (block is CameraBlock && block.remote) {
            if (player.isShiftKeyDown) {
                if (level.isClientSide) openScreenFor(level, pos)
                return InteractionResult.sidedSuccess(level.isClientSide)
            }
            if (!level.isClientSide) {
                setArmed(stack, pos)
                actionBar(player, "Camera armed — click where the eye should sit", false)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        // 2) Armed: this click places the viewpoint.
        val camPos = armedPos(stack) ?: return InteractionResult.PASS
        if (player.isShiftKeyDown) {
            if (!level.isClientSide) {
                setArmed(stack, null)
                actionBar(player, "Cable disarmed", true)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (level.isClientSide) {
            commitEye(level, camPos, context)
        } else {
            setArmed(stack, null)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    /** CLIENT: snapped face point → facing-relative eye, send + open the UI. */
    private fun commitEye(level: Level, camPos: BlockPos, context: UseOnContext) {
        val be = level.getBlockEntity(camPos) as? CameraBlockEntity ?: return
        // Snap to the welding-style 3×3 face grid (corners / edge midpoints /
        // centre) — the exact point the overlay highlights under the crosshair.
        val eyeWorld = snappedEyeWorld(level, context.clickedPos, context.clickedFace, context.clickLocation)
        val local = worldToFacingLocal(level, camPos, eyeWorld) ?: return
        val max = CameraBlockEntity.MAX_EYE_OFFSET
        val r = local.x.coerceIn(-max, max)
        val u = local.y.coerceIn(-max, max)
        val f = local.z.coerceIn(-max, max)
        val yaw = be.remoteEyeYaw()
        val pitch = be.remoteEyePitch()
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            dev.nitka.nodewire.net.SetCameraEyePacket(camPos, r, u, f, yaw, pitch, clear = false),
        )
        dev.nitka.nodewire.client.screen.RemoteCameraScreen.open(camPos, r, u, f, yaw, pitch)
    }

    private fun openScreenFor(level: Level, camPos: BlockPos) {
        val be = level.getBlockEntity(camPos) as? CameraBlockEntity ?: return
        val eye = be.remoteEye() ?: doubleArrayOf(0.0, 0.0, 0.0)
        dev.nitka.nodewire.client.screen.RemoteCameraScreen.open(
            camPos, eye[0], eye[1], eye[2], be.remoteEyeYaw(), be.remoteEyePitch(),
        )
    }

    private fun actionBar(player: Player, text: String, muted: Boolean) {
        player.displayClientMessage(
            Component.literal(text).withStyle(if (muted) ChatFormatting.GRAY else ChatFormatting.AQUA),
            true,
        )
    }

    companion object {
        private const val NBT_ARMED = "cable_camera"

        fun armedPos(stack: ItemStack): BlockPos? {
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            return if (tag.contains(NBT_ARMED)) BlockPos.of(tag.getLong(NBT_ARMED)) else null
        }

        fun setArmed(stack: ItemStack, pos: BlockPos?) {
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            if (pos == null) tag.remove(NBT_ARMED) else tag.putLong(NBT_ARMED, pos.asLong())
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        /**
         * World point → the camera's facing-relative frame (right, up,
         * forward), Sable-aware: the basis vectors are the block's local axes
         * pushed through the live pose, so the inverse is three dot products.
         */
        fun worldToFacingLocal(level: Level, camPos: BlockPos, world: Vec3): Vec3? {
            val ref = EndpointRef.from(level, camPos)
            val center = ref.worldCenter(level) ?: Vec3.atCenterOf(camPos)
            val facing = level.getBlockState(camPos).getValue(CameraBlock.FACING)
            val fwdLocal = Vec3.atLowerCornerOf(facing.normal)
            val rightLocal = Vec3.atLowerCornerOf(facing.clockWise.normal)
            val upLocal = Vec3(0.0, 1.0, 0.0)
            val fwd = ref.worldDirection(level, fwdLocal) ?: fwdLocal
            val right = ref.worldDirection(level, rightLocal) ?: rightLocal
            val up = ref.worldDirection(level, upLocal) ?: upLocal
            val d = world.subtract(center)
            return Vec3(d.dot(right), d.dot(up), d.dot(fwd))
        }

        // ── welding-style face snap (Synaxis' 3×3 grid: {0, ½, 1}) ────────

        private val SNAPS = doubleArrayOf(0.0, 0.5, 1.0)
        private const val SNAP_NUDGE = 0.03

        /** The 9 snap points of [face] in block-local 0..1 coords (nudged just
         *  off the face so markers/eyes never z-fight the surface). */
        fun snapPointsLocal(face: net.minecraft.core.Direction): List<Vec3> {
            val n = Vec3.atLowerCornerOf(face.normal)
            val out = ArrayList<Vec3>(9)
            for (a in SNAPS) for (b in SNAPS) {
                val p = when (face.axis) {
                    net.minecraft.core.Direction.Axis.Y ->
                        Vec3(a, if (face.axisDirection == net.minecraft.core.Direction.AxisDirection.POSITIVE) 1.0 else 0.0, b)
                    net.minecraft.core.Direction.Axis.X ->
                        Vec3(if (face.axisDirection == net.minecraft.core.Direction.AxisDirection.POSITIVE) 1.0 else 0.0, a, b)
                    else ->
                        Vec3(a, b, if (face.axisDirection == net.minecraft.core.Direction.AxisDirection.POSITIVE) 1.0 else 0.0)
                }
                out.add(p.add(n.scale(SNAP_NUDGE)))
            }
            return out
        }

        /** Block-local 0..1 point → world, through the live Sable pose. */
        fun localToWorld(level: Level, pos: BlockPos, local: Vec3): Vec3 {
            val ref = EndpointRef.from(level, pos)
            val center = ref.worldCenter(level) ?: Vec3.atCenterOf(pos)
            val rel = local.subtract(0.5, 0.5, 0.5)
            return center.add(ref.worldDirection(level, rel) ?: rel)
        }

        /** Nearest snap point of ([pos], [face]) to the world-space [hit]. */
        fun nearestSnapWorld(level: Level, pos: BlockPos, face: net.minecraft.core.Direction, hit: Vec3): Vec3 =
            snapPointsLocal(face)
                .map { localToWorld(level, pos, it) }
                .minByOrNull { it.distanceToSqr(hit) } ?: hit

        /** The point the cable's second click binds: the highlighted snap. */
        fun snappedEyeWorld(level: Level, pos: BlockPos, face: net.minecraft.core.Direction, hit: Vec3): Vec3 =
            nearestSnapWorld(level, pos, face, hit)

        /** Facing-relative (right, up, forward) → block-local grid vector. */
        fun facingLocalToGrid(facing: net.minecraft.core.Direction, r: Double, u: Double, f: Double): Vec3 {
            val fwd = Vec3.atLowerCornerOf(facing.normal)
            val right = Vec3.atLowerCornerOf(facing.clockWise.normal)
            return Vec3(
                right.x * r + f * fwd.x,
                u,
                right.z * r + f * fwd.z,
            )
        }
    }
}
