package dev.nitka.nodewire.script.ui

import dev.nitka.nodewire.script.Video
import dev.nitka.nodewire.script.VideoCanvas

/**
 * Declarative flexbox layout for script video surfaces — "Compose-shaped",
 * immediate-mode. Each `ui { … }` call inside `draw(screen) { … }` builds a
 * pure-data [UiSpec] tree and hands it to the engine
 * ([VideoCanvas.renderUi]), which lays it out with the mod's Yoga flexbox at
 * the surface size and paints through the ordinary canvas verbs. Positions
 * and sizes come from flexbox instead of manual pixel math:
 *
 * ```
 * draw(screen) {
 *     image(cam.value)                       // raw verbs still work
 *     ui(pad = 2) {
 *         row(justify = Justify.SpaceBetween, bg = 0xFF000000) {
 *             text("RWS-01", 0xFF8BE08B)
 *             text("ZOOM 2×", 0xFF8BE08B)
 *         }
 *         spacer()                           // soaks up the middle
 *         row(gap = 4, align = Align.Center, bg = 0xFF000000) {
 *             text("AZ 0°", 0xFF8BE08B)
 *             spacer()
 *             rect(4, 4, 0xFFFF3B3B)         // indicator dot
 *         }
 *     }
 * }
 * ```
 *
 * **Sandbox + classpath note.** This file is the script-facing API: it lives
 * under the allowlisted `dev.nitka.nodewire.script.` prefix AND deliberately
 * references ONLY script types + primitives — no Yoga, no client classes —
 * so it deserializes cleanly on the script compiler's classpath
 * (script-api.jar) and links cleanly in the sandbox. The flexbox engine stays
 * on the far side of [VideoCanvas.renderUi].
 *
 * **Colors** are packed-ARGB `Long`s like every other canvas verb; `0` means
 * "none" for backgrounds/borders.
 */

/** Main-axis distribution (CSS `justify-content`). */
enum class Justify { Start, Center, End, SpaceBetween, SpaceAround, SpaceEvenly }

/** Cross-axis alignment (CSS `align-items`). */
enum class Align { Start, Center, End, Stretch }

/** What a [UiSpec] node paints. */
enum class UiKind { CONTAINER, TEXT, IMAGE }

/**
 * One node of the declarative tree — pure data, built by [UiScope], consumed
 * by the engine ([VideoCanvas.renderUi]). Scripts never construct it directly.
 */
class UiSpec internal constructor(
    val kind: UiKind,
    val vertical: Boolean = true,
    val grow: Float = 0f,
    val shrink: Float = 0f,
    val width: Int? = null,
    val height: Int? = null,
    val pad: Int = 0,
    val gap: Int = 0,
    val justify: Justify = Justify.Start,
    val align: Align = Align.Start,
    val bg: Long = 0L,
    val borderColor: Long = 0L,
    val borderW: Int = 1,
    val text: String? = null,
    val textColor: Long = 0L,
    val video: Video? = null,
) {
    /** Children in declaration order. Internal — populated by [UiScope]. */
    val children: MutableList<UiSpec> = ArrayList()
}

@DslMarker
annotation class VideoUiDsl

/**
 * Build the root [UiSpec] for [VideoCanvas.ui] (the script-facing entry is a
 * MEMBER of [VideoCanvas] — top-level functions don't resolve off the packed
 * script-api.jar; see the member's KDoc). Kept here so the whole tree-building
 * vocabulary lives in one file.
 */
fun buildUiSpec(
    pad: Int,
    gap: Int,
    justify: Justify,
    align: Align,
    block: UiScope.() -> Unit,
): UiSpec {
    val root = UiSpec(
        kind = UiKind.CONTAINER,
        vertical = true,
        pad = pad,
        gap = gap,
        justify = justify,
        align = align,
    )
    UiScope(root).block()
    return root
}

/** Builder scope for one container's children. */
@VideoUiDsl
class UiScope internal constructor(private val parent: UiSpec) {

    /** Vertical container (children top→bottom). */
    fun column(
        grow: Float = 0f,
        shrink: Float = 0f,
        width: Int? = null,
        height: Int? = null,
        pad: Int = 0,
        gap: Int = 0,
        justify: Justify = Justify.Start,
        align: Align = Align.Start,
        bg: Long = 0L,
        borderColor: Long = 0L,
        borderW: Int = 1,
        block: UiScope.() -> Unit = {},
    ) = container(true, grow, shrink, width, height, pad, gap, justify, align, bg, borderColor, borderW, block)

    /** Horizontal container (children left→right). */
    fun row(
        grow: Float = 0f,
        shrink: Float = 0f,
        width: Int? = null,
        height: Int? = null,
        pad: Int = 0,
        gap: Int = 0,
        justify: Justify = Justify.Start,
        align: Align = Align.Start,
        bg: Long = 0L,
        borderColor: Long = 0L,
        borderW: Int = 1,
        block: UiScope.() -> Unit = {},
    ) = container(false, grow, shrink, width, height, pad, gap, justify, align, bg, borderColor, borderW, block)

    /** One line of text, sized by the surface's real font metrics. */
    fun text(s: String, color: Long = 0xFF_FF_FF_FFL) {
        parent.children += UiSpec(kind = UiKind.TEXT, text = s, textColor = color)
    }

    /**
     * Flexible empty space. Default [grow] = 1 — one `spacer()` between two
     * groups pushes them apart; fixed [width]/[height] make a rigid gap.
     */
    fun spacer(grow: Float = 1f, width: Int? = null, height: Int? = null) {
        parent.children += UiSpec(kind = UiKind.CONTAINER, grow = grow, width = width, height = height)
    }

    /** Fixed-size solid swatch (indicator dots, bars, separators). */
    fun rect(width: Int, height: Int, color: Long, grow: Float = 0f) {
        parent.children += UiSpec(kind = UiKind.CONTAINER, grow = grow, width = width, height = height, bg = color)
    }

    /**
     * Blit a [Video] feed into this element's layout box. Default [grow] = 1
     * so an `image(cam.value)` inside a column takes the leftover space —
     * give explicit [width]/[height] for a thumbnail.
     */
    fun image(video: Video, grow: Float = 1f, width: Int? = null, height: Int? = null) {
        parent.children += UiSpec(kind = UiKind.IMAGE, grow = grow, width = width, height = height, video = video)
    }

    private fun container(
        vertical: Boolean,
        grow: Float,
        shrink: Float,
        width: Int?,
        height: Int?,
        pad: Int,
        gap: Int,
        justify: Justify,
        align: Align,
        bg: Long,
        borderColor: Long,
        borderW: Int,
        block: UiScope.() -> Unit,
    ) {
        val el = UiSpec(
            kind = UiKind.CONTAINER,
            vertical = vertical,
            grow = grow,
            shrink = shrink,
            width = width,
            height = height,
            pad = pad,
            gap = gap,
            justify = justify,
            align = align,
            bg = bg,
            borderColor = borderColor,
            borderW = borderW,
        )
        parent.children += el
        UiScope(el).block()
    }
}
