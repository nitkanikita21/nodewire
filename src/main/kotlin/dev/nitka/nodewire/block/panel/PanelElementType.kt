package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType

enum class PanelPinDir { OUTPUT, INPUT }

/**
 * One pin an element type exposes. [name] `""` marks the element's PRIMARY pin
 * — its wire id stays the bare cell-anchor id (`"type@x,y"`, the pre-multi-pin
 * format, so existing saved links keep resolving); named pins get
 * `"type@x,y:name"` (see [PanelPins.pinId]).
 */
data class ElementPin(
    val name: String,
    val dir: PanelPinDir,
    val type: PinType,
)

/**
 * A Control Panel element type: fixed grid footprint + the full set of pins it
 * exposes. An element is self-contained pin-wise — any number of outputs and
 * inputs (a toggle publishes its state AND accepts a remote `set`; the
 * mini-screen accepts video AND publishes `touch`/`touch_down`). Screens come
 * in fixed size VARIANTS (`screen_small` … `screen_full`) instead of resizing.
 */
data class PanelElementType(
    val id: String,
    val cols: Int,
    val rows: Int,
    val pins: List<ElementPin>,
) {
    val outputs: List<ElementPin> get() = pins.filter { it.dir == PanelPinDir.OUTPUT }
    val inputs: List<ElementPin> get() = pins.filter { it.dir == PanelPinDir.INPUT }

    /** Operable = the player can click it (it produces something). */
    val interactive: Boolean get() = outputs.isNotEmpty()
}

object PanelElements {
    private fun out(type: PinType) = ElementPin("", PanelPinDir.OUTPUT, type)
    private fun inp(type: PinType) = ElementPin("", PanelPinDir.INPUT, type)

    val ALL: List<PanelElementType> = listOf(
        // Player inputs — primary OUT is the operated state; `set` lets the
        // graph drive the control remotely (state syncs back to the visual).
        PanelElementType(
            "toggle", 2, 2,
            listOf(out(PinType.BOOL), ElementPin("set", PanelPinDir.INPUT, PinType.BOOL)),
        ),
        PanelElementType("momentary", 2, 2, listOf(out(PinType.BOOL))),
        PanelElementType(
            "selector", 2, 2,
            listOf(out(PinType.INT), ElementPin("set", PanelPinDir.INPUT, PinType.INT)),
        ),
        PanelElementType(
            "slider", 4, 1,
            listOf(out(PinType.FLOAT), ElementPin("set", PanelPinDir.INPUT, PinType.FLOAT)),
        ),
        PanelElementType(
            "knob", 3, 3,
            listOf(out(PinType.FLOAT), ElementPin("set", PanelPinDir.INPUT, PinType.FLOAT)),
        ),
        // Indicators — primary IN drives the display.
        PanelElementType("lamp", 1, 1, listOf(inp(PinType.BOOL))),
        PanelElementType("bar", 4, 1, listOf(inp(PinType.FLOAT))),
        PanelElementType("numeric", 4, 2, listOf(inp(PinType.FLOAT))),
        // Mini-screens: video in + a touch surface out (tiny touch-screens) in
        // a range of fixed footprints. OFF by default — the `enable` pin powers
        // them up; a dark screen shows no video and ignores taps.
        screen("screen", 4, 4),
        screen("screen_small", 2, 2),
        screen("screen_wide", 8, 4),
        screen("screen_large", 8, 8),
        screen("screen_full", 16, 16),
        // Decorative.
        PanelElementType("label", 2, 1, emptyList()),
    )

    private val byId = ALL.associateBy { it.id }
    fun byId(id: String): PanelElementType? = byId[id]

    /** Every screen variant shares the screen pin set + behaviour. */
    fun isScreen(typeId: String): Boolean = typeId == "screen" || typeId.startsWith("screen_")

    private fun screen(id: String, cols: Int, rows: Int) = PanelElementType(
        id, cols, rows,
        listOf(
            inp(PinType.VIDEO),
            ElementPin("enable", PanelPinDir.INPUT, PinType.BOOL),
            ElementPin("touch", PanelPinDir.OUTPUT, PinType.VEC2),
            ElementPin("touch_down", PanelPinDir.OUTPUT, PinType.BOOL),
        ),
    )
}
