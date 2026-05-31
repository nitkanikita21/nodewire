package dev.nitka.nodewire.integration.sensor

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.fluids.FluidUtil
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler

/**
 * One row per readable data point. [read] pulls the value from a resolved
 * world [BlockEntity] (+ optional [side] / [filter]) via VANILLA caps:
 * `Capabilities.ItemHandler.BLOCK`, `Capabilities.FluidHandler.BLOCK`, and
 * `BlockState.getAnalogOutputSignal`. No Create dependency, no reflection.
 *
 * Failures (absent cap, unloaded chunk, despawned sub-level) surface either
 * as a null cap (-> the read returns the type default) or as a throw caught
 * by [SensorStatePipeline.snapshot]'s runCatching -> PinValue.default. The
 * evaluator never sees an exception.
 *
 * Catalog is FIXED by spec — never reorder/rename (the enum name is the NBT
 * `reading` key).
 */
enum class SensorReading(
    val displayName: String,
    val pinType: PinType,
    val needsFilter: Boolean,
    val read: (be: BlockEntity, side: Direction?, filter: ItemStack?) -> PinValue,
) {
    ITEM_COUNT("Item count", PinType.INT, false, { be, side, _ ->
        PinValue.Int(itemCount(items(be, side)))
    }),
    ITEM_FILL("Item fill", PinType.FLOAT, false, { be, side, _ ->
        PinValue.Float(itemFill(items(be, side)))
    }),
    COUNT_OF("Count of item", PinType.INT, true, { be, side, filter ->
        PinValue.Int(countOf(items(be, side), filter))
    }),
    CONTAINS("Contains item", PinType.BOOL, true, { be, side, filter ->
        PinValue.Bool(contains(items(be, side), filter))
    }),
    FLUID_MB("Fluid (mB)", PinType.INT, false, { be, side, _ ->
        PinValue.Int(fluidMb(fluids(be, side)))
    }),
    FLUID_FILL("Fluid fill", PinType.FLOAT, false, { be, side, _ ->
        PinValue.Float(fluidFill(fluids(be, side)))
    }),
    FLUID_IS("Fluid is", PinType.BOOL, true, { be, side, filter ->
        val f = fluids(be, side)
        val target = filterFluid(filter)
        val hit = f != null && target != null && (0 until f.tanks).any { i ->
            val fs = f.getFluidInTank(i)
            !fs.isEmpty && fs.fluid == target
        }
        PinValue.Bool(hit)
    }),
    COMPARATOR("Comparator signal", PinType.INT, false, { be, _, _ ->
        val level = be.level
        if (level == null) {
            PinValue.Int(0)
        } else {
            val state = be.blockState
            PinValue.Int(
                if (state.hasAnalogOutputSignal()) state.getAnalogOutputSignal(level, be.blockPos) else 0,
            )
        }
    }),
    IS_EMPTY("Is empty", PinType.BOOL, false, { be, side, _ ->
        PinValue.Bool(isEmpty(items(be, side), fluids(be, side)))
    }),
    IS_FULL("Is full", PinType.BOOL, false, { be, side, _ ->
        PinValue.Bool(isFull(items(be, side), fluids(be, side)))
    });

    companion object {
        fun fromName(name: String): SensorReading? = entries.firstOrNull { it.name == name }

        /** Item handler off the resolved BE (BLOCK cap, side may be null). */
        private fun items(be: BlockEntity, side: Direction?): IItemHandler? =
            be.level?.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, side)

        /** Fluid handler off the resolved BE (BLOCK cap, side may be null). */
        private fun fluids(be: BlockEntity, side: Direction?): IFluidHandler? =
            be.level?.getCapability(Capabilities.FluidHandler.BLOCK, be.blockPos, side)

        /** Fluid type carried by a filter item (e.g. a bucket), else null. */
        private fun filterFluid(filter: ItemStack?) =
            if (filter == null || filter.isEmpty) {
                null
            } else {
                FluidUtil.getFluidContained(filter)
                    .filter { !it.isEmpty }
                    .map { it.fluid }
                    .orElse(null)
            }

        /**
         * Readings the target's present caps support — used by the link-tool
         * picker so a chest never lists FLUID_*. Item caps -> ITEM_*; fluid
         * caps -> FLUID_*; an analog signal -> COMPARATOR; IS_EMPTY/IS_FULL
         * whenever any container cap is present.
         */
        fun supportedBy(be: BlockEntity?): List<SensorReading> {
            if (be == null) return emptyList()
            val level = be.level ?: return emptyList()
            val hasItems = level.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, null) != null
            val hasFluids = level.getCapability(Capabilities.FluidHandler.BLOCK, be.blockPos, null) != null
            val hasComparator = be.blockState.hasAnalogOutputSignal()
            return entries.filter { r ->
                when (r) {
                    ITEM_COUNT, ITEM_FILL, COUNT_OF, CONTAINS -> hasItems
                    FLUID_MB, FLUID_FILL, FLUID_IS -> hasFluids
                    COMPARATOR -> hasComparator
                    IS_EMPTY, IS_FULL -> hasItems || hasFluids
                }
            }
        }

        // ── Pure per-handler math (unit-tested directly via handler fakes) ──

        internal fun itemCount(h: IItemHandler?): Int =
            if (h == null) 0 else (0 until h.slots).sumOf { h.getStackInSlot(it).count }

        internal fun itemFill(h: IItemHandler?): Float =
            if (h == null || h.slots == 0) {
                0f
            } else {
                (0 until h.slots).count { !h.getStackInSlot(it).isEmpty }.toFloat() / h.slots
            }

        internal fun countOf(h: IItemHandler?, filter: ItemStack?): Int =
            if (h == null || filter == null || filter.isEmpty) {
                0
            } else {
                (0 until h.slots).sumOf { val s = h.getStackInSlot(it); if (s.`is`(filter.item)) s.count else 0 }
            }

        internal fun contains(h: IItemHandler?, filter: ItemStack?): Boolean =
            h != null && filter != null && !filter.isEmpty &&
                (0 until h.slots).any { h.getStackInSlot(it).`is`(filter.item) }

        internal fun fluidMb(f: IFluidHandler?): Int =
            if (f == null) 0 else (0 until f.tanks).sumOf { f.getFluidInTank(it).amount }

        internal fun fluidFill(f: IFluidHandler?): Float {
            if (f == null) return 0f
            var a = 0
            var c = 0
            for (i in 0 until f.tanks) {
                a += f.getFluidInTank(i).amount
                c += f.getTankCapacity(i)
            }
            return if (c == 0) 0f else a.toFloat() / c
        }

        internal fun isEmpty(h: IItemHandler?, f: IFluidHandler?): Boolean {
            if (h == null && f == null) return false
            val ie = h == null || (0 until h.slots).all { h.getStackInSlot(it).isEmpty }
            val fe = f == null || (0 until f.tanks).all { f.getFluidInTank(it).isEmpty }
            return ie && fe
        }

        internal fun isFull(h: IItemHandler?, f: IFluidHandler?): Boolean {
            if (h == null && f == null) return false
            val itf = h == null || (0 until h.slots).all {
                val s = h.getStackInSlot(it)
                !s.isEmpty && s.count >= h.getSlotLimit(it)
            }
            val ftf = f == null || (0 until f.tanks).all {
                f.getFluidInTank(it).amount >= f.getTankCapacity(it) && f.getTankCapacity(it) > 0
            }
            return itf && ftf
        }
    }
}
