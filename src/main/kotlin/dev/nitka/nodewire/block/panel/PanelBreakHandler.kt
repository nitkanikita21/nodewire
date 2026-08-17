package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.UUID

/**
 * Block-like BREAKING for panel elements: left-clicking a Control Panel with
 * an element under the crosshair pops THAT element (returns its item; creative
 * breaks silently, like blocks) instead of mining the panel. Aiming at bare
 * plate leaves vanilla block breaking untouched, so the panel itself is still
 * removable. Placing already works block-like (right-click with the element
 * item), so together the panel edits like a tiny build grid.
 *
 * The precise hit comes from the player's own raycast against the panel's
 * dynamic shape (plate + raised element boxes), so the popped element is
 * exactly the outlined one — any approach angle.
 */
// Registered explicitly from Nodewire.init on the game bus. The
// @EventBusSubscriber annotation does NOT work here: KFF's auto-subscriber
// covers Kotlin objects on the MOD bus, so this handler was silently never
// registered and left-clicking an element did nothing at all.
object PanelBreakHandler {

    /** Server-side re-fire guard: LeftClickBlock spams while the button is
     *  held — without a cooldown one press machine-guns through neighbours. */
    private val lastBreak = HashMap<UUID, Long>()
    private const val BREAK_COOLDOWN_TICKS = 6L

    private val LOG = com.mojang.logging.LogUtils.getLogger()
    private var lastTrace = 0L

    /** Why a left click did NOT remove an element — rate-limited, client only
     *  (the server sees a click at all only once the client forwards it). */
    private fun trace(level: net.minecraft.world.level.Level, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastTrace < 500L) return
        lastTrace = now
        LOG.info("[NW-PANEL] {} left click ignored: {}", if (level.isClientSide) "client" else "server", reason)
    }

    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val level = event.level
        val player = event.entity
        if (player.isSpectator) return

        // Player-precise raycast (both sides) — the panel's shape includes the
        // raised element boxes, so the hit lands on the outlined element.
        // The player's own raycast is the source of truth, NOT event.pos:
        // on a Sable sub-level the two disagree (the event reported the ship's
        // armour while the crosshair was on the panel), and the pick is what
        // the outline the player sees is drawn from.
        val hit = player.pick(player.blockInteractionRange(), 1f, false) as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.block !is ControlPanelBlock) {
            // Log both targets: if the crosshair is on a panel yet neither
            // position is one, the ray is passing THROUGH it into whatever it
            // is mounted on, which is a shape problem rather than an event one.
            trace(
                level,
                "event=${level.getBlockState(event.pos).block.descriptionId}@${event.pos.toShortString()} " +
                    "pick=${state.block.descriptionId}@${pos.toShortString()}",
            )
            return
        }
        val gh = ControlPanelBlock.gridHit(state, pos, hit.location)
        if (gh == null) {
            trace(level, "hit did not map to a grid cell")
            return
        }
        val be = level.getBlockEntity(pos) as? ControlPanelBlockEntity ?: return
        val element = be.elementAt(gh.cell)
        if (element == null) {
            trace(level, "cell ${gh.cell.x},${gh.cell.y} is bare plate")
            return // bare plate → mine the block
        }

        // An element is targeted: never mine the panel through it.
        event.isCanceled = true

        // Cancelling on the CLIENT stops the attack packet from ever being
        // sent, so the server-side half of this event never fires and the
        // element was never actually removed. Ask for the removal explicitly
        // instead — the same packet the Panel Key uses, validated server-side.
        if (level.isClientSide) {
            val now = level.gameTime
            val last = lastBreak[player.uuid] ?: Long.MIN_VALUE
            if (now - last < BREAK_COOLDOWN_TICKS) return
            lastBreak[player.uuid] = now
            LOG.info("[NW-PANEL] removing element at cell {},{}", gh.cell.x, gh.cell.y)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                dev.nitka.nodewire.net.RemoveElementPacket(pos, gh.cell.x, gh.cell.y),
            )
            return
        }

        val now = level.gameTime
        val last = lastBreak[player.uuid] ?: Long.MIN_VALUE
        if (now - last < BREAK_COOLDOWN_TICKS) return
        lastBreak[player.uuid] = now

        val removed = be.removeElementAt(gh.cell) ?: return
        if (!player.abilities.instabuild) {
            dropElementItem(level, pos, removed, player)
        }
    }

    /** Drop the element's item at its spot on the plate (block-drop feel). */
    private fun dropElementItem(
        level: net.minecraft.world.level.Level,
        pos: net.minecraft.core.BlockPos,
        removed: PlacedElement,
        player: Player,
    ) {
        val item = Registry.PANEL_ELEMENT_ITEMS[removed.typeId]?.get() ?: return
        val state = level.getBlockState(pos)
        val face = state.getValue(ControlPanelBlock.FACE)
        val spin = state.getValue(ControlPanelBlock.SPIN)
        val centerU = (removed.cellX + removed.cols / 2.0) / 16.0
        val centerV = (removed.cellY + removed.rows / 2.0) / 16.0
        val local = PanelSpace.local(centerU, centerV, face, spin, 0.1)
        val entity = ItemEntity(
            level,
            pos.x + local[0], pos.y + local[1], pos.z + local[2],
            ItemStack(item),
        )
        entity.setDefaultPickUpDelay()
        level.addFreshEntity(entity)
    }
}
