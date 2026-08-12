package dev.nitka.nodewire.block.panel

import com.mojang.serialization.Codec

/**
 * The mutable list of [PlacedElement]s on a single Control Panel, plus the pure
 * occupancy bookkeeping over the 16×16 [PanelGrid]. Kept separate from the
 * BlockEntity so the placement/removal logic is unit-testable without a world.
 *
 * Every element occupies a fixed `cols×rows` footprint anchored at its
 * `(cellX, cellY)`; [add] rejects anything that falls off the grid or overlaps
 * an existing element.
 */
class PanelElementStore {
    private val elements: MutableList<PlacedElement> = mutableListOf()

    /** Snapshot copy — safe to iterate while the store mutates. */
    fun all(): List<PlacedElement> = elements.toList()

    fun isEmpty(): Boolean = elements.isEmpty()

    /** Every grid cell currently covered by some element. */
    fun occupied(): Set<PanelGrid.Cell> =
        elements.flatMapTo(HashSet()) { cellsOf(it) }

    /** Add [e] if it fits the grid and overlaps nothing. Returns false otherwise. */
    fun add(e: PlacedElement): Boolean {
        val anchor = PanelGrid.Cell(e.cellX, e.cellY)
        if (!PanelGrid.fits(anchor, e.cols, e.rows)) return false
        if (PanelGrid.overlaps(occupied(), anchor, e.cols, e.rows)) return false
        elements.add(e)
        return true
    }

    /** The element whose footprint covers [cell], or null. */
    fun elementAt(cell: PanelGrid.Cell): PlacedElement? =
        elements.firstOrNull { cell in cellsOf(it) }

    /** Remove and return the element covering [cell], or null if none. */
    fun removeAt(cell: PanelGrid.Cell): PlacedElement? {
        val e = elementAt(cell) ?: return null
        elements.remove(e)
        return e
    }

    /** Set the runtime value of the element covering [cell]; true if it changed. */
    fun setValue(cell: PanelGrid.Cell, value: Double): Boolean {
        val idx = elements.indexOfFirst { cell in cellsOf(it) }
        if (idx < 0 || elements[idx].value == value) return false
        elements[idx] = elements[idx].copy(value = value)
        return true
    }

    /** Replace the config of the element covering [cell]; true if there was one. */
    fun setConfig(cell: PanelGrid.Cell, config: net.minecraft.nbt.CompoundTag): Boolean {
        val idx = elements.indexOfFirst { cell in cellsOf(it) }
        if (idx < 0) return false
        elements[idx] = elements[idx].copy(config = config)
        return true
    }

    /**
     * Replace config AND footprint of the element covering [cell] (a
     * config-driven size, e.g. push_button's button count). The resize is
     * validated against the grid and every OTHER element; on a conflict the new
     * config still applies but the old footprint is kept. Returns true if an
     * element was there.
     */
    fun setConfigAndSize(cell: PanelGrid.Cell, config: net.minecraft.nbt.CompoundTag, cols: Int, rows: Int): Boolean {
        val idx = elements.indexOfFirst { cell in cellsOf(it) }
        if (idx < 0) return false
        val e = elements[idx]
        val anchor = PanelGrid.Cell(e.cellX, e.cellY)
        val others = elements.filterIndexed { i, _ -> i != idx }.flatMapTo(HashSet()) { cellsOf(it) }
        val fits = PanelGrid.fits(anchor, cols, rows) && !PanelGrid.overlaps(others, anchor, cols, rows)
        elements[idx] = if (fits) e.copy(config = config, cols = cols, rows = rows) else e.copy(config = config)
        return true
    }

    fun clear() = elements.clear()

    fun load(list: List<PlacedElement>) {
        elements.clear()
        elements.addAll(list)
    }

    private fun cellsOf(e: PlacedElement): List<PanelGrid.Cell> =
        PanelGrid.footprintCells(PanelGrid.Cell(e.cellX, e.cellY), e.cols, e.rows)

    companion object {
        /** Codec for the whole element list (BE persistence). */
        val LIST_CODEC: Codec<List<PlacedElement>> = PlacedElement.CODEC.listOf()
    }
}
