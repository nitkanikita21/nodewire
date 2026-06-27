package dev.nitka.nodewire.client.link

import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValueConversion
import dev.nitka.nodewire.item.ChannelLinkToolItem
import dev.nitka.nodewire.link.HostlessLinkStore
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkSink
import dev.nitka.nodewire.link.PinPorts
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Client-side state for the Channel Link Tool's inline pin picker — the small
 * hover window ([LinkHudRenderer]) that replaces the old full-screen pickers
 * AND the deleted Link Manager screen.
 *
 * Every client tick [update] raycasts the crosshair, enumerates the targeted
 * block's pins through [PinPorts], reads the block's own incoming [PinLink]s
 * (client-synced via `getUpdateTag`), and tags each row for the current phase:
 *
 *  * No source armed → the block's OUTPUT pins are active arm candidates; any
 *    already-bound INPUT pin is also shown so it can be unbound.
 *  * A source armed → the INPUT pins the source type converts into are active
 *    commit targets; bound inputs stay selectable (for unbind) even when the
 *    armed type can't feed them.
 *
 * Scroll ([scroll]) moves the highlight across **active** rows. RMB acts on
 * [highlightedPin] (arm / commit). MMB acts on [highlightedLink] (unbind), and
 * the hovered link's source block is highlighted in-world so you can see where
 * the wire goes. All state is render-thread/client-tick local.
 */
object LinkHud {

    /**
     * One pin row. [active] = the scroll highlight can land here. [canBind] =
     * RMB arms (output, arming phase) or commits a link (compatible input,
     * targeting phase). [link] = the incoming [PinLink] feeding this input pin
     * (null for outputs / unbound inputs) — drives the source readout, the
     * MMB-unbind, and the source-block highlight.
     */
    data class Row(
        val pin: LinkPin,
        val output: Boolean,
        val active: Boolean,
        val canBind: Boolean,
        val link: PinLink?,
    )

    var targetPos: BlockPos? = null
        private set
    var rows: List<Row> = emptyList()
        private set
    var highlight: Int = -1
        private set

    /** Header info: the armed source's label/type, or null while arming. */
    var armedLabel: String? = null
        private set
    var armedType: PinType? = null
        private set

    /** True when the crosshair is on the armed source's own block (no self-link). */
    var sameAsSource: Boolean = false
        private set

    private var lastPos: BlockPos? = null

    /** Recompute from the crosshair + tool state. Call once per client tick. */
    fun update() {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return clear()
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()
        val stack = player.mainHandItem
        if (stack.item !is ChannelLinkToolItem) return clear()
        if (ChannelLinkToolItem.readMode(stack) != ChannelLinkToolItem.Mode.LINK) return clear()
        val hit = mc.hitResult as? BlockHitResult ?: return clear()
        if (hit.type != HitResult.Type.BLOCK) return clear()

        val pos = hit.blockPos
        val face = hit.direction
        val armed = ChannelLinkToolItem.readArmedSource(stack)
        armedLabel = armed?.label
        armedType = armed?.type
        sameAsSource = armed != null && armed.source.payload.blockPos == pos

        val port = PinPorts.at(level, pos, face)
        if (port == null) return clear()
        val ctx = LinkContext(level, pos, level.getBlockState(pos), face)
        val outs = port.pinOutputs(ctx)
        val ins = port.pinInputs(ctx)

        // Incoming links feeding this block's input pins — used to read out a
        // bound input's source + offer unbind. A PinLinkSink BE carries them in
        // NBT (client-synced via getUpdateTag); a FOREIGN target (CBC mount, …)
        // has no BE of ours, so its host-less links come from the level store.
        val sinkLinks: List<PinLink> =
            (level.getBlockEntity(pos) as? PinLinkSink)?.pinLinks()?.toList()
                ?: hostlessLinksFor(level, pos)
        fun linkFor(pinId: String): PinLink? = sinkLinks.firstOrNull { it.targetPin == pinId }

        val newRows = buildList {
            if (armed == null) {
                // Arming: this block's OUTPUT pins are the arm candidates…
                outs.forEach { add(Row(it, output = true, active = true, canBind = true, link = null)) }
                // …plus any already-bound INPUT, shown so it can be unbound.
                ins.forEach { p ->
                    val l = linkFor(p.id)
                    if (l != null) add(Row(p, output = false, active = true, canBind = false, link = l))
                }
            } else {
                // Targeting: INPUT pins. Compatible ones commit; bound ones are
                // selectable for unbind even when incompatible.
                ins.forEach { p ->
                    val l = linkFor(p.id)
                    val compatible = !sameAsSource && PinValueConversion.canConvert(armed.type, p.type)
                    add(Row(p, output = false, active = compatible || l != null, canBind = compatible, link = l))
                }
            }
        }

        // Reset the highlight to the first active row whenever the targeted
        // block changes; otherwise keep the player's scroll position.
        if (pos != lastPos) {
            lastPos = pos
            highlight = newRows.indexOfFirst { it.active }
        }
        rows = newRows
        targetPos = pos
        if (highlight !in rows.indices || !rows[highlight].active) {
            highlight = rows.indexOfFirst { it.active }
        }

        // Light up the source block of the hovered bound row (through walls), so
        // you see where the wire goes. Refreshed each tick → fades when you
        // scroll off / look away.
        highlightedLink()?.let { BlockHighlightRenderer.highlight(it.source, HOVER_HIGHLIGHT_MS) }
    }

    /** Move the highlight to the next/previous ACTIVE row (wraps). */
    fun scroll(dir: Int) {
        val actives = rows.indices.filter { rows[it].active }
        if (actives.isEmpty()) return
        val cur = actives.indexOf(highlight).coerceAtLeast(0)
        val next = Math.floorMod(cur + (if (dir > 0) 1 else -1), actives.size)
        highlight = actives[next]
    }

    /** The pin RMB acts on (arm / commit), or null when none is selectable. */
    fun highlightedPin(): LinkPin? = rows.getOrNull(highlight)?.takeIf { it.canBind }?.pin

    /** The incoming link MMB unbinds (and whose source is highlighted), or null. */
    fun highlightedLink(): PinLink? = rows.getOrNull(highlight)?.link

    /** Whether the window currently offers at least one selectable row (gates
     *  whether plain scroll cycles pins vs. falls through to the hotbar). */
    fun hasActive(): Boolean = rows.any { it.active }

    fun clear() {
        targetPos = null
        rows = emptyList()
        highlight = -1
        armedLabel = null
        armedType = null
        sameAsSource = false
        lastPos = null
    }

    /**
     * Host-less links targeting [pos], surfaced as synthetic [PinLink]s so the
     * existing bound-row readout + MMB-unbind path works on a foreign target.
     * These live in the server-side [HostlessLinkStore], so we read them off the
     * INTEGRATED server — singleplayer only; on a dedicated server foreign-target
     * rows don't surface (rebind to replace). Defensive: an off-thread store read
     * can rarely race the level tick, so a failure degrades to "no rows".
     */
    private fun hostlessLinksFor(level: Level, pos: BlockPos): List<PinLink> {
        val server = Minecraft.getInstance().singleplayerServer ?: return emptyList()
        val serverLevel = server.getLevel(level.dimension()) ?: return emptyList()
        return runCatching {
            HostlessLinkStore.of(serverLevel).links()
                .filter { it.target.payload.blockPos == pos }
                .map { PinLink(it.source, it.sourcePin, it.targetPin) }
        }.getOrDefault(emptyList())
    }

    private const val HOVER_HIGHLIGHT_MS = 250L
}
