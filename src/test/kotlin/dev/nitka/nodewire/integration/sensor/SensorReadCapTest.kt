package dev.nitka.nodewire.integration.sensor

import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Covers the pure per-handler arithmetic of [SensorReading] (the `internal`
 * helpers added in Task 1) against hand-rolled cap fakes — no Mockito, no
 * world resolve.
 *
 * Coverage boundary (verified empirically): this project's `:test` source set
 * has NO Minecraft mod-loading bootstrap — `Bootstrap.bootStrap()` NPEs in
 * `FeatureFlagLoader.loadModdedFlags()` (LoadingModList.get() is null off a
 * real run), and even `ItemStack.EMPTY` / `FluidStack.EMPTY` throw at static
 * init. So NO real [ItemStack]/[FluidStack] can be constructed here. The
 * stack-bearing arithmetic (itemCount/itemFill/countOf/contains/fluidMb sums,
 * full/empty with populated slots, item-type-only filter matching) is exercised
 * in the in-client manual pass (plan Task 11), per the plan's documented
 * fallback. The unit test pins down every helper's degenerate branches that DO
 * NOT need a stack: null caps, empty/zero-tank handlers, and the no-cap guards.
 */
class SensorReadCapTest {

    /** Zero-slot/zero-tank-or-arbitrary-slot-count item fake (never returns a stack). */
    private class EmptyItems(private val slots: Int, private val limit: Int = 64) : IItemHandler {
        override fun getSlots() = slots
        override fun getStackInSlot(slot: Int): ItemStack = throw AssertionError("must not read a stack")
        override fun getSlotLimit(slot: Int) = limit
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean) = stack
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean) = throw AssertionError("unused")
        override fun isItemValid(slot: Int, stack: ItemStack) = true
    }

    /** Zero-tank fluid fake (never returns a FluidStack). */
    private class EmptyFluids(private val tanks: Int, private val cap: Int = 0) : IFluidHandler {
        override fun getTanks() = tanks
        override fun getFluidInTank(tank: Int): FluidStack = throw AssertionError("must not read a fluid")
        override fun getTankCapacity(tank: Int) = cap
        override fun isFluidValid(tank: Int, stack: FluidStack) = true
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction) = 0
        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack =
            throw AssertionError("unused")
        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack =
            throw AssertionError("unused")
    }

    @Test fun `null item handler reads as zero`() {
        assertEquals(0, SensorReading.itemCount(null))
        assertEquals(0f, SensorReading.itemFill(null), 0f)
        assertEquals(0, SensorReading.countOf(null, null))
        assertFalse(SensorReading.contains(null, null))
    }

    @Test fun `item fill of an empty (zero-slot) handler is zero`() {
        // slots == 0 -> short-circuits before any getStackInSlot read.
        assertEquals(0f, SensorReading.itemFill(EmptyItems(slots = 0)), 0f)
    }

    @Test fun `null fluid handler reads as zero`() {
        assertEquals(0, SensorReading.fluidMb(null))
        assertEquals(0f, SensorReading.fluidFill(null), 0f)
    }

    @Test fun `fluid fill with zero total capacity is zero`() {
        // tanks == 0 -> loop never runs, capacity stays 0 -> 0f (no division by zero).
        assertEquals(0f, SensorReading.fluidFill(EmptyFluids(tanks = 0)), 0f)
    }

    @Test fun `empty and full with no caps return false`() {
        assertFalse(SensorReading.isEmpty(null, null)) // no caps -> false
        assertFalse(SensorReading.isFull(null, null)) // no caps -> false
    }

    @Test fun `empty handlers (zero slots and zero tanks) read as empty, not full`() {
        val items = EmptyItems(slots = 0)
        val fluids = EmptyFluids(tanks = 0)
        // all()/all() over an empty range is vacuously true on both sides.
        org.junit.jupiter.api.Assertions.assertTrue(SensorReading.isEmpty(items, fluids))
        org.junit.jupiter.api.Assertions.assertTrue(SensorReading.isFull(items, fluids))
    }
}
