package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType

enum class PanelPinDir { OUTPUT, INPUT }

/**
 * One pin an element type exposes. [name] `""` marks the element's PRIMARY pin
 * — its wire id stays the bare cell-anchor id (`"type@x,y"`); named pins get
 * `"type@x,y:name"` (see [PanelPins.pinId]).
 */
data class ElementPin(
    val name: String,
    val dir: PanelPinDir,
    val type: PinType,
)

/**
 * A Control Panel element type: grid footprint + pins. The element set is the
 * **Dashpanels module catalog** (visuals ported 1:1, MIT — BoxxedDev), plus
 * our mini-screens (video, no Dashpanels analogue). `push_button` reshapes its
 * footprint from config (button count/gap) — see [PanelPins] for its dynamic
 * per-button pins.
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
    private fun out(name: String, type: PinType) = ElementPin(name, PanelPinDir.OUTPUT, type)
    private fun inp(name: String, type: PinType) = ElementPin(name, PanelPinDir.INPUT, type)

    val ALL: List<PanelElementType> = listOf(
        // ── player inputs ─────────────────────────────────────────────────
        PanelElementType("switch", 2, 3, listOf(out("", PinType.BOOL), inp("set", PinType.BOOL))),
        PanelElementType("momentary", 3, 3, listOf(out("", PinType.BOOL))),
        // Selected index out (0 = none, 1..n); per-button BOOL pins are dynamic
        // (config `buttons`), appended in PanelPins.
        PanelElementType("push_button", 2, 3, listOf(out("", PinType.INT))),
        PanelElementType("key_switch", 2, 2, listOf(out("", PinType.BOOL))),
        // Cover is pin-driven (open), the big button is the output.
        PanelElementType("emergency", 4, 4, listOf(out("", PinType.BOOL), inp("open", PinType.BOOL))),
        PanelElementType("lever", 3, 5, listOf(out("", PinType.INT), inp("set", PinType.INT))),
        PanelElementType("knob", 2, 2, listOf(out("", PinType.FLOAT), inp("set", PinType.FLOAT))),
        PanelElementType(
            "joystick", 4, 4,
            listOf(out("", PinType.VEC2), out("trigger", PinType.BOOL)),
        ),
        // ── indicators / outputs ──────────────────────────────────────────
        PanelElementType("bulb", 1, 2, listOf(inp("", PinType.BOOL))),
        PanelElementType("seven_segment", 6, 4, listOf(inp("", PinType.FLOAT))),
        PanelElementType("buzzer", 4, 4, listOf(inp("", PinType.BOOL))),
        // ── decorative ────────────────────────────────────────────────────
        PanelElementType("label", 6, 2, emptyList()),
        // ── mini-screens (ours — no Dashpanels analogue) ──────────────────
        screen("screen", 4, 4),
        screen("screen_small", 2, 2),
        screen("screen_wide", 8, 4),
        screen("screen_large", 8, 8),
        screen("screen_full", 16, 16),
    )

    private val byId = ALL.associateBy { it.id }
    fun byId(id: String): PanelElementType? = byId[id]

    /** Every screen variant shares the screen pin set + behaviour. */
    fun isScreen(typeId: String): Boolean = typeId == "screen" || typeId.startsWith("screen_")

    /** push_button footprint from its config: n buttons of 2 cells + gaps. */
    fun pushButtonFootprint(buttons: Int, gap: Int): Pair<Int, Int> =
        (buttons * 2 + gap * (buttons - 1)) to 3

    private fun screen(id: String, cols: Int, rows: Int) = PanelElementType(
        id, cols, rows,
        listOf(
            inp("", PinType.VIDEO),
            inp("enable", PinType.BOOL),
            out("touch", PinType.VEC2),
            out("touch_down", PinType.BOOL),
        ),
    )
}
