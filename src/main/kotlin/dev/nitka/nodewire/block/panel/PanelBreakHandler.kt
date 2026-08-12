package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
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
@EventBusSubscriber(modid = Nodewire.ID)
object PanelBreakHandler {

    /** Server-side re-fire guard: LeftClickBlock spams while the button is
     *  held — without a cooldown one press machine-guns through neighbours. */
    private val lastBreak = HashMap<UUID, Long>()
    private const val BREAK_COOLDOWN_TICKS = 6L

    @SubscribeEvent
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val level = event.level
        val state = level.getBlockState(event.pos)
        if (state.block !is ControlPanelBlock) return
        val player = event.entity
        if (player.isSpectator) return

        // Player-precise raycast (both sides) — the panel's shape includes the
        // raised element boxes, so the hit lands on the outlined element.
        val hit = player.pick(player.blockInteractionRange(), 1f, false) as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK || hit.blockPos != event.pos) return
        val gh = ControlPanelBlock.gridHit(state, event.pos, hit.location) ?: return
        val be = level.getBlockEntity(event.pos) as? ControlPanelBlockEntity ?: return
        val element = be.elementAt(gh.cell) ?: return // bare plate → mine the block

        // An element is targeted: never mine the panel through it.
        event.isCanceled = true
        if (level.isClientSide) return

        val now = level.gameTime
        val last = lastBreak[player.uuid] ?: Long.MIN_VALUE
        if (now - last < BREAK_COOLDOWN_TICKS) return
        lastBreak[player.uuid] = now

        val removed = be.removeElementAt(gh.cell) ?: return
        if (!player.abilities.instabuild) {
            dropElementItem(level, event.pos, removed, player)
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
