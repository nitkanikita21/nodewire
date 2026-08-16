package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.link.LinkPin

/**
 * Derives a Control Panel's [LinkPin]s from its placed elements. Every element
 * exposes ALL of its catalog pins ([PanelElementType.pins]):
 *
 *  * the PRIMARY pin (spec name `""`) keeps the bare cell-anchor id
 *    (`"type@x,y"` — the pre-multi-pin format, so old links keep resolving);
 *  * named pins are suffixed: `"type@x,y:set"`, `"type@x,y:touch"`, …
 *
 * [baseId]/[pinName] split an incoming wire id back into the element anchor +
 * the spec name for the BE's read/write dispatch.
 */
object PanelPins {
    /** Wire id for [pin] of element [e]. */
    fun pinId(e: PlacedElement, pin: ElementPin): String = pinId(e, pin.name)

    /** Wire id for pin [name] of element [e] (primary when name is empty). */
    fun pinId(e: PlacedElement, name: String): String =
        if (name.isEmpty()) e.pinId() else "${e.pinId()}:$name"

    /** The element cell-anchor part of a wire id (`"toggle@0,0:set"` → `"toggle@0,0"`). */
    fun baseId(pinId: String): String = pinId.substringBefore(':')

    /** The spec-name part of a wire id (`""` for a primary pin). */
    fun pinName(pinId: String): String =
        if (':' in pinId) pinId.substringAfter(':') else ""

    /** Every OUTPUT pin of every element, in element order. */
    fun outputs(elements: List<PlacedElement>): List<LinkPin> = pins(elements, PanelPinDir.OUTPUT)

    /** Every INPUT pin of every element, in element order. */
    fun inputs(elements: List<PlacedElement>): List<LinkPin> = pins(elements, PanelPinDir.INPUT)

    private fun pins(elements: List<PlacedElement>, dir: PanelPinDir): List<LinkPin> =
        elements.flatMap { e -> pinsFor(e).filter { it.dir == dir }.map { LinkPin(pinId(e, it), it.type) } }

    /** All pin specs for a placed element — catalog pins plus dynamic extras
     *  (push_button grows one BOOL out per configured button). */
    fun pinsFor(e: PlacedElement): List<ElementPin> {
        val type = PanelElements.byId(e.typeId) ?: return emptyList()
        if (e.typeId == "push_button") {
            val buttons = (if (e.config.contains("buttons")) e.config.getInt("buttons") else 1).coerceIn(1, 8)
            return type.pins + (1..buttons).map {
                ElementPin("b$it", PanelPinDir.OUTPUT, dev.nitka.nodewire.graph.PinType.BOOL)
            }
        }
        if (e.typeId == "joystick_ctrl") {
            // Embedded Control Block: one output pin per configured Binding,
            // plus the block's session-state pins — same surface as the BE.
            return PanelControlBindings.of(e.config).map {
                ElementPin(it.pin, PanelPinDir.OUTPUT, it.type)
            } + listOf(
                ElementPin("active", PanelPinDir.OUTPUT, dev.nitka.nodewire.graph.PinType.BOOL),
                ElementPin("mouse_captured", PanelPinDir.OUTPUT, dev.nitka.nodewire.graph.PinType.BOOL),
            )
        }
        return type.pins
    }
}
