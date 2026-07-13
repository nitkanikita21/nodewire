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
    fun pinId(e: PlacedElement, pin: ElementPin): String =
        if (pin.name.isEmpty()) e.pinId() else "${e.pinId()}:${pin.name}"

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
        elements.flatMap { e ->
            val type = PanelElements.byId(e.typeId) ?: return@flatMap emptyList<LinkPin>()
            type.pins.filter { it.dir == dir }.map { LinkPin(pinId(e, it), it.type) }
        }
}
