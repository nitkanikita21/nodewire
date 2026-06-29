package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType

enum class PanelPinDir { OUTPUT, INPUT, NONE }

data class PanelElementType(
    val id: String,
    val cols: Int,
    val rows: Int,
    val pinDir: PanelPinDir,
    val pinType: PinType?,
    val resizable: Boolean = false,
)

object PanelElements {
    val ALL: List<PanelElementType> = listOf(
        PanelElementType("toggle",    2, 2, PanelPinDir.OUTPUT, PinType.BOOL),
        PanelElementType("momentary", 2, 2, PanelPinDir.OUTPUT, PinType.BOOL),
        PanelElementType("selector",  2, 2, PanelPinDir.OUTPUT, PinType.INT),
        PanelElementType("slider",    4, 1, PanelPinDir.OUTPUT, PinType.FLOAT),
        PanelElementType("knob",      3, 3, PanelPinDir.OUTPUT, PinType.FLOAT),
        PanelElementType("lamp",      1, 1, PanelPinDir.INPUT,  PinType.BOOL),
        PanelElementType("bar",       4, 1, PanelPinDir.INPUT,  PinType.FLOAT),
        PanelElementType("numeric",   4, 2, PanelPinDir.INPUT,  PinType.FLOAT),
        PanelElementType("screen",    4, 4, PanelPinDir.INPUT,  PinType.VIDEO, resizable = true),
        PanelElementType("label",     2, 1, PanelPinDir.NONE,   null),
    )
    private val byId = ALL.associateBy { it.id }
    fun byId(id: String): PanelElementType? = byId[id]
}
