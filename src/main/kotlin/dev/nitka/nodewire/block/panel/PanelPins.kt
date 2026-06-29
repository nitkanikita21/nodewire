package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.link.LinkPin

/**
 * Derives a Control Panel's [LinkPin]s from its placed elements.
 *
 * Each element with a directional pin ([PanelPinDir.OUTPUT] / [PanelPinDir.INPUT])
 * contributes exactly one pin: id = [PlacedElement.pinId] (`"type@x,y"`), type =
 * the catalog [PanelElementType.pinType]. Elements with [PanelPinDir.NONE] (e.g.
 * `label`) or an unknown type id contribute nothing. The [LinkPin] label defaults
 * to its id, matching the rest of the link system.
 */
object PanelPins {
    /** One [LinkPin] per `OUTPUT` element, in element order. */
    fun outputs(elements: List<PlacedElement>): List<LinkPin> = pins(elements, PanelPinDir.OUTPUT)

    /** One [LinkPin] per `INPUT` element, in element order. */
    fun inputs(elements: List<PlacedElement>): List<LinkPin> = pins(elements, PanelPinDir.INPUT)

    private fun pins(elements: List<PlacedElement>, dir: PanelPinDir): List<LinkPin> =
        elements.mapNotNull { e ->
            val type = PanelElements.byId(e.typeId) ?: return@mapNotNull null
            if (type.pinDir != dir) return@mapNotNull null
            val pinType = type.pinType ?: return@mapNotNull null
            LinkPin(e.pinId(), pinType)
        }
}
