package dev.nitka.nodewire.client.wire

import dev.nitka.nodewire.block.ChannelBinding
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.link.HostlessLinkStore
import net.createmod.catnip.outliner.Outliner
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * Always-on world rendering of channel bindings between logic blocks, drawn the
 * Create / Drive-By-Wire way — through catnip's [Outliner] (Create renders the
 * global instance every frame with a robust, Veil/Sodium-safe pass) instead of a
 * hand-rolled immediate-mode quad batch. Endpoint positions come from
 * [dev.nitka.nodewire.endpoint.EndpointRef.worldCenter], which is Sable-aware
 * (a block on a sub-level reports its LIVE render-pose world centre), so wires
 * track ships/aircraft for free.
 *
 * Only shown while the local player holds a ChannelLinkToolItem. Each wire is
 * refreshed (kept alive) every frame under a stable key; retired wires (tool put
 * away, binding removed) are [Outliner.remove]d so they vanish at once.
 *
 * Per shared endpoint, wires fan out radially so several lines out of one block
 * don't Z-fight as one.
 */
object WireWorldRenderer {

    private const val WIRE_WIDTH = 1.0f / 16f

    /** Endpoint-outline colour — every block taking part in a wire gets one. */
    private val BOX_COLOR = 0xFFB83030.toInt()

    /** Keys shown last frame — diffed so retired wires get removed promptly. */
    private val shownKeys = HashSet<Any>()

    private fun holdingLinkTool(player: net.minecraft.client.player.LocalPlayer): Boolean {
        val link = dev.nitka.nodewire.Registry.CHANNEL_LINK_TOOL.get()
        return player.mainHandItem.`is`(link) || player.offhandItem.`is`(link)
    }

    /** Run once per frame (gated to one render stage). Updates the Outliner line
     *  set; Create draws the global Outliner itself. */
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        val outliner = Outliner.getInstance()
        val frameKeys = HashSet<Any>()
        // Every block that takes part in ANY wire (source or target, of any kind)
        // is outlined exactly once from here — so the box logic is uniform instead
        // of each collect pass deciding for itself. pos.asLong() -> world centre.
        val boxes = HashMap<Long, Vec3>()

        if (holdingLinkTool(player)) {
            val tracked = ClientLogicBlockTracker.all()
            if (tracked.isNotEmpty()) collect(level, tracked, outliner, frameKeys, boxes)
            collectHostless(level, outliner, frameKeys, boxes)
            collectForeignSinks(level, outliner, frameKeys, boxes)
            for ((key, center) in boxes) box(outliner, frameKeys, "nw:box:$key", level, net.minecraft.core.BlockPos.of(key), center, BOX_COLOR)
        }

        // Retire keys no longer present (tool put away, binding removed).
        val it = shownKeys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in frameKeys) { outliner.remove(k); it.remove() }
        }
        shownKeys.addAll(frameKeys)
    }

    private fun collect(
        level: net.minecraft.world.level.Level,
        tracked: Collection<LogicBlockEntity>,
        outliner: Outliner,
        frameKeys: MutableSet<Any>,
        boxes: MutableMap<Long, Vec3>,
    ) {
        // ── gather + fan-slot assignment (stable iteration = stable slots) ──
        val outCount = HashMap<Long, Int>()
        val inCount = HashMap<Long, Int>()
        val bindList = mutableListOf<RenderBinding>()
        for (source in tracked) {
            for (b in source.bindingsSnapshot()) {
                val sk = source.blockPos.asLong()
                val dk = b.target.payload.blockPos.asLong()
                bindList.add(RenderBinding(source, b, outCount.merge(sk, 1, Int::plus)!! - 1, inCount.merge(dk, 1, Int::plus)!! - 1))
            }
        }
        val sideList = mutableListOf<RenderSideBinding>()
        for (source in tracked) {
            for (sb in source.sideBindingsSnapshot()) {
                val sk = source.blockPos.asLong()
                sideList.add(RenderSideBinding(source, sb, outCount.merge(sk, 1, Int::plus)!! - 1))
            }
        }
        val pinLinkList = mutableListOf<RenderPinLink>()
        for (logic in tracked) {
            for (pl in logic.pinLinksSnapshot()) {
                val dk = logic.blockPos.asLong()
                pinLinkList.add(RenderPinLink(logic, pl, inCount.merge(dk, 1, Int::plus)!! - 1))
            }
        }
        if (bindList.isEmpty() && sideList.isEmpty() && pinLinkList.isEmpty()) return

        val outTotal = HashMap<Long, Int>()
        val inTotal = HashMap<Long, Int>()
        for (rb in bindList) {
            outTotal.merge(rb.source.blockPos.asLong(), 1, Int::plus)
            inTotal.merge(rb.binding.target.payload.blockPos.asLong(), 1, Int::plus)
        }
        for (sb in sideList) outTotal.merge(sb.source.blockPos.asLong(), 1, Int::plus)
        for (rr in pinLinkList) inTotal.merge(rr.logic.blockPos.asLong(), 1, Int::plus)

        // ── emit Outliner lines; positions are Sable-aware via worldCenter ──
        for (rb in bindList) {
            val sk = rb.source.blockPos.asLong()
            val dk = rb.binding.target.payload.blockPos.asLong()
            val srcC = sourceWorldCenter(rb.source, level) ?: continue
            val dstC = rb.binding.target.worldCenter(level) ?: continue
            line(outliner, frameKeys, "nw:bind:$sk:$dk:${rb.binding.sourceChannelName}",
                fanOffset(srcC, rb.srcIdx, outTotal[sk]!!), fanOffset(dstC, rb.dstIdx, inTotal[dk]!!),
                colorForBinding(rb.source, rb.binding.sourceChannelName))
            boxes[sk] = srcC; boxes[dk] = dstC
        }
        for (rr in pinLinkList) {
            val dk = rr.logic.blockPos.asLong()
            val srcC = rr.link.source.worldCenter(level) ?: Vec3.atCenterOf(rr.link.source.payload.blockPos)
            val dstC = sourceWorldCenter(rr.logic, level) ?: continue
            val sk = rr.link.source.payload.blockPos.asLong()
            val srcIdx = outCount.merge(sk, 1, Int::plus)!! - 1
            outTotal.merge(sk, 1, Int::plus)
            val tgtType = rr.logic.graph.nodes.values.firstOrNull {
                it.typeKey.path == "channel_input" && it.config.getString("name") == rr.link.targetPin
            }?.let { PinType.fromName(it.config.getString("type")) }
            line(outliner, frameKeys, "nw:pin:$sk:$dk:${rr.link.targetPin}",
                fanOffset(srcC, srcIdx, outTotal[sk]!!), fanOffset(dstC, rr.dstIdx, inTotal[dk]!!),
                colorForType(tgtType ?: PinType.REDSTONE))
            boxes[sk] = srcC; boxes[dk] = dstC
        }
        for (sb in sideList) {
            val sk = sb.source.blockPos.asLong()
            val srcC = sourceWorldCenter(sb.source, level) ?: continue
            val tCenter = sb.binding.target.worldCenter(level) ?: continue
            val n = sb.binding.targetSide.normal
            val wn = sb.binding.target.worldDirection(level, Vec3(n.x.toDouble(), n.y.toDouble(), n.z.toDouble())) ?: continue
            val dst = Vec3(tCenter.x + wn.x * 0.5, tCenter.y + wn.y * 0.5, tCenter.z + wn.z * 0.5)
            line(outliner, frameKeys, "nw:side:$sk:${sb.binding.targetSide}:${sb.binding.sourceChannelName}",
                fanOffset(srcC, sb.srcIdx, outTotal[sk]!!), dst, colorForBinding(sb.source, sb.binding.sourceChannelName))
            boxes[sk] = srcC; boxes[sb.binding.target.payload.blockPos.asLong()] = tCenter
        }
    }

    /**
     * Host-less links have no BE hosting them — they live in the per-level
     * [HostlessLinkStore], so the logic-block walk can't surface them. Read them
     * straight off the INTEGRATED server (singleplayer only; on a dedicated server
     * the store isn't synced to the client, so foreign->foreign wires won't draw —
     * same limitation as the LinkHud surfacing). Endpoints are Sable-aware via
     * [worldCenter]. Drawn center-to-center (these are rare foreign->foreign links).
     */
    private fun collectHostless(level: net.minecraft.world.level.Level, outliner: Outliner, frameKeys: MutableSet<Any>, boxes: MutableMap<Long, Vec3>) {
        val server = Minecraft.getInstance().singleplayerServer ?: return
        val serverLevel = server.getLevel(level.dimension()) ?: return
        val links = runCatching { HostlessLinkStore.of(serverLevel).links() }.getOrNull() ?: return
        for (link in links) {
            val srcC = link.source.worldCenter(level) ?: Vec3.atCenterOf(link.source.payload.blockPos)
            val dstC = link.target.worldCenter(level) ?: Vec3.atCenterOf(link.target.payload.blockPos)
            val sk = link.source.payload.blockPos.asLong()
            val dk = link.target.payload.blockPos.asLong()
            line(outliner, frameKeys, "nw:hl:$sk:$dk:${link.sourcePin}:${link.targetPin}", srcC, dstC, 0xFFE8C85C.toInt())
            boxes[sk] = srcC; boxes[dk] = dstC
        }
    }

    /**
     * Links landing on a foreign [dev.nitka.nodewire.link.PinLinkSink] BE (the
     * offroad wheel mount) — these are BE-local (stored on that BE), not in the
     * host-less store, and the BE isn't a LogicBlockEntity, so the logic walk
     * misses them. The BE replicates its `pin_links` to the client, so we read
     * them off the client copy here. Removed BEs are pruned defensively.
     */
    private fun collectForeignSinks(level: net.minecraft.world.level.Level, outliner: Outliner, frameKeys: MutableSet<Any>, boxes: MutableMap<Long, Vec3>) {
        val sinks = ClientForeignSinkTracker.all()
        if (sinks.isEmpty()) return
        for (be in sinks.toList()) {
            if (be.isRemoved || be.level !== level) { ClientForeignSinkTracker.unregister(be); continue }
            val sink = be as? dev.nitka.nodewire.link.PinLinkSink ?: continue
            val dstC = dev.nitka.nodewire.endpoint.EndpointRef.from(level, be.blockPos).worldCenter(level)
                ?: Vec3.atCenterOf(be.blockPos)
            for (link in sink.pinLinks()) {
                val srcC = link.source.worldCenter(level) ?: Vec3.atCenterOf(link.source.payload.blockPos)
                val sk = link.source.payload.blockPos.asLong()
                val dk = be.blockPos.asLong()
                line(outliner, frameKeys, "nw:fs:$sk:$dk:${link.targetPin}", srcC, dstC, 0xFFE8C85C.toInt())
                boxes[sk] = srcC; boxes[dk] = dstC
            }
        }
    }

    private fun line(outliner: Outliner, frameKeys: MutableSet<Any>, key: String, a: Vec3, b: Vec3, color: Int) {
        outliner.showLine(key, a, b).lineWidth(WIRE_WIDTH).colored(color).disableLineNormals().disableCull()
        frameKeys.add(key)
    }

    /**
     * Block outline as 12 edge lines, ORIENTED by the block's Sable pose — an
     * axis-aligned [AABB] would ignore a sub-level's rotation and float off a
     * block on an angled ship. The basis vectors are rotated through
     * [dev.nitka.nodewire.endpoint.EndpointRef.worldDirection] (identity for a
     * plain block, the render-pose rotation on a sub-level), so the box hugs the
     * block whatever its orientation. [center] is already the Sable-aware centre.
     */
    private fun box(
        outliner: Outliner, frameKeys: MutableSet<Any>, key: String,
        level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos, center: Vec3, color: Int,
    ) {
        val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level, pos)
        val h = 0.51
        val ex = (ref.worldDirection(level, Vec3(1.0, 0.0, 0.0)) ?: Vec3(1.0, 0.0, 0.0)).scale(h)
        val ey = (ref.worldDirection(level, Vec3(0.0, 1.0, 0.0)) ?: Vec3(0.0, 1.0, 0.0)).scale(h)
        val ez = (ref.worldDirection(level, Vec3(0.0, 0.0, 1.0)) ?: Vec3(0.0, 0.0, 1.0)).scale(h)
        fun corner(sx: Double, sy: Double, sz: Double): Vec3 =
            center.add(ex.scale(sx)).add(ey.scale(sy)).add(ez.scale(sz))
        val c = arrayOf(
            corner(-1.0, -1.0, -1.0), corner(1.0, -1.0, -1.0), corner(1.0, -1.0, 1.0), corner(-1.0, -1.0, 1.0),
            corner(-1.0, 1.0, -1.0), corner(1.0, 1.0, -1.0), corner(1.0, 1.0, 1.0), corner(-1.0, 1.0, 1.0),
        )
        val edges = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        var i = 0
        while (i < edges.size) {
            val lk = "$key:e${i / 2}"
            outliner.showLine(lk, c[edges[i]], c[edges[i + 1]]).lineWidth(WIRE_WIDTH).colored(color).disableLineNormals().disableCull()
            frameKeys.add(lk)
            i += 2
        }
    }

    private fun sourceWorldCenter(be: LogicBlockEntity, level: net.minecraft.world.level.Level): Vec3? =
        dev.nitka.nodewire.endpoint.EndpointRef.from(level, be.blockPos).worldCenter(level)

    private fun fanOffset(center: Vec3, index: Int, total: Int): Vec3 {
        if (total <= 1) return center
        val radius = 0.22
        val angle = (Math.PI * 2.0 * index) / total
        return Vec3(center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius)
    }

    private fun colorForBinding(source: LogicBlockEntity, channelName: String): Int {
        val type = source.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output" && it.config.getString("name") == channelName
        }?.config?.getString("type")?.let { PinType.fromName(it) } ?: return 0xFFFFFFFF.toInt()
        return colorForType(type)
    }

    private fun colorForType(type: PinType): Int = when (type) {
        PinType.BOOL -> 0xFFE85C5C.toInt()
        PinType.INT -> 0xFF5CC8E8.toInt()
        PinType.FLOAT -> 0xFFE8C85C.toInt()
        PinType.REDSTONE -> 0xFFB83030.toInt()
        PinType.STRING -> 0xFFF08A4A.toInt()
        PinType.VEC2 -> 0xFF7CE85C.toInt()
        PinType.VEC3 -> 0xFFACE85C.toInt()
        PinType.QUAT -> 0xFFC87CE8.toInt()
        PinType.VIDEO -> 0xFF4A4AF0.toInt()
        PinType.ANY -> 0xFF9CA3AF.toInt()
    }

    private data class RenderSideBinding(val source: LogicBlockEntity, val binding: dev.nitka.nodewire.block.SideBinding, val srcIdx: Int)
    private data class RenderBinding(val source: LogicBlockEntity, val binding: ChannelBinding, val srcIdx: Int, val dstIdx: Int)
    private data class RenderPinLink(val logic: LogicBlockEntity, val link: dev.nitka.nodewire.link.PinLink, val dstIdx: Int)
}
