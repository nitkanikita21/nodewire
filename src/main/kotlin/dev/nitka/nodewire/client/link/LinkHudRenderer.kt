package dev.nitka.nodewire.client.link

import dev.nitka.nodewire.graph.PinType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.neoforged.neoforge.client.event.RenderGuiEvent

/**
 * Draws the Channel Link Tool's inline pin window (state in [LinkHud]) as a
 * small HUD panel next to the crosshair — the in-world replacement for the old
 * full-screen pin pickers and Link Manager. Rows are type-colored; the
 * active/selectable ones are bright, incompatible ones are dimmed but still
 * visible (so you see *why* a pin can't be picked), and the scroll-highlighted
 * row gets an accent bar. A bound input's right side reads out its source block
 * (`← x, y, z`); when one is highlighted, a footer hints that the middle mouse
 * button unbinds it.
 */
object LinkHudRenderer {

    private const val MAX_VISIBLE = 7
    private const val PAD = 5
    private const val DOT = 4
    private const val DOT_GAP = 5
    private const val TAG_GAP = 8
    private const val LABEL_MAX_W = 130

    // chrome (ARGB)
    private const val BG = 0xE6_14_14_1A.toInt()
    private const val BORDER = 0xFF_30_30_38.toInt()
    private const val TEXT = 0xFF_E8_E8_EC.toInt()
    private const val TEXT_DIM = 0xFF_56_56_60.toInt()
    private const val MUTED = 0xFF_94_94_A0.toInt()
    private const val ACCENT = 0xFF_4A_9E_FF.toInt()
    private const val HILITE_BG = 0x33_4A_9E_FF.toInt()

    private const val UNBIND_HINT = "MMB: unbind"

    fun onRenderGui(event: RenderGuiEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.player == null) return
        val rows = LinkHud.rows
        if (rows.isEmpty() || LinkHud.targetPos == null) return

        val g = event.guiGraphics
        val font = mc.font
        val lineH = font.lineHeight
        val rowH = lineH + 3

        val header = headerText()
        val highlight = LinkHud.highlight
        val visible = minOf(rows.size, MAX_VISIBLE)
        val start = (highlight - MAX_VISIBLE / 2)
            .coerceIn(0, maxOf(0, rows.size - visible))
        val end = (start + visible).coerceAtMost(rows.size)

        // Right-aligned readout per row: a bound input shows its source block
        // ("← x, y, z"); everything else shows the in/out tag.
        fun rightText(i: Int): String {
            val row = rows[i]
            return row.link?.let { "← ${it.source.payload.blockPos.toShortString()}" }
                ?: if (row.output) "out" else "in"
        }

        // ── measure ──
        fun fit(s: String): String {
            if (font.width(s) <= LABEL_MAX_W) return s
            var t = s
            while (t.isNotEmpty() && font.width("$t…") > LABEL_MAX_W) t = t.dropLast(1)
            return "$t…"
        }
        val unbindHint = LinkHud.highlightedLink() != null
        val labels = (start until end).associateWith { fit(rows[it].pin.label) }
        var innerW = font.width(header)
        for (i in start until end) {
            val w = DOT + DOT_GAP + font.width(labels.getValue(i)) + TAG_GAP + font.width(rightText(i))
            if (w > innerW) innerW = w
        }
        if (unbindHint) innerW = maxOf(innerW, font.width(UNBIND_HINT))
        val width = innerW + PAD * 2
        val height = PAD * 2 + (lineH + 4) + (end - start) * rowH + (if (unbindHint) lineH + 2 else 0)

        // ── position: just right of the crosshair, vertically centered, clamped ──
        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val x = (sw / 2 + 14).coerceAtMost(sw - width - 4)
        val y = (sh / 2 - height / 2).coerceIn(4, maxOf(4, sh - height - 4))

        // ── frame ──
        g.fill(x, y, x + width, y + height, BG)
        g.fill(x, y, x + width, y + 1, BORDER)
        g.fill(x, y + height - 1, x + width, y + height, BORDER)
        g.fill(x, y, x + 1, y + height, BORDER)
        g.fill(x + width - 1, y, x + width, y + height, BORDER)

        // ── header ──
        val tx = x + PAD
        var ty = y + PAD
        g.drawString(font, header, tx, ty, if (LinkHud.armedType != null) ACCENT else MUTED)
        ty += lineH + 4

        // ── rows ──
        for (i in start until end) {
            val row = rows[i]
            val rowY = ty + (i - start) * rowH
            if (i == highlight && row.active) {
                g.fill(x + 2, rowY - 2, x + width - 2, rowY + lineH + 1, HILITE_BG)
            }
            val dotColor = if (row.active) pinArgb(row.pin.type) else dim(pinArgb(row.pin.type))
            g.fill(tx, rowY + 1, tx + DOT, rowY + 1 + DOT, dotColor)
            val labelColor = when {
                i == highlight && row.active -> TEXT
                row.active -> TEXT
                else -> TEXT_DIM
            }
            g.drawString(font, labels.getValue(i), tx + DOT + DOT_GAP, rowY, labelColor)
            val right = rightText(i)
            val rightColor = when {
                row.link != null -> ACCENT          // bound: source readout pops
                row.active -> MUTED
                else -> TEXT_DIM
            }
            g.drawString(font, right, x + width - PAD - font.width(right), rowY, rightColor)
        }

        // ── footer: unbind hint for the highlighted bound row ──
        if (unbindHint) {
            val hy = ty + (end - start) * rowH + 1
            g.drawString(font, UNBIND_HINT, tx, hy, MUTED)
        }

        // ── overflow arrows ──
        if (start > 0) g.drawString(font, "▲", x + width - PAD - font.width("▲"), y + PAD, MUTED)
        if (end < rows.size && !unbindHint) {
            g.drawString(font, "▼", x + width - PAD - font.width("▼"), y + height - PAD - lineH, MUTED)
        }
    }

    private fun headerText(): String {
        val label = LinkHud.armedLabel
        val type = LinkHud.armedType
        return when {
            LinkHud.sameAsSource -> "Same block — pick another target"
            label != null && type != null -> "→ $label  (${type.name.lowercase()})"
            else -> "Pick source pin"
        }
    }

    /** Halve the alpha of a color (dim an inactive pin's dot). */
    private fun dim(argb: Int): Int = (argb and 0x00FFFFFF) or (((argb ushr 24) / 2 and 0xFF) shl 24)

    private fun pinArgb(t: PinType): Int = when (t) {
        PinType.BOOL -> 0xFF_E8_5C_5C.toInt()
        PinType.INT -> 0xFF_5C_C8_E8.toInt()
        PinType.FLOAT -> 0xFF_E8_C8_5C.toInt()
        PinType.REDSTONE -> 0xFF_B8_30_30.toInt()
        PinType.STRING -> 0xFF_F0_8A_4A.toInt()
        PinType.VEC2 -> 0xFF_7C_E8_5C.toInt()
        PinType.VEC3 -> 0xFF_AC_E8_5C.toInt()
        PinType.QUAT -> 0xFF_C8_7C_E8.toInt()
        PinType.VIDEO -> 0xFF_4A_4A_F0.toInt()
        PinType.ANY -> 0xFF_9C_A3_AF.toInt()
    }
}
