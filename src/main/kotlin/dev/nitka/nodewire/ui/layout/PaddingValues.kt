package dev.nitka.nodewire.ui.layout

/**
 * Per-edge padding in MC GUI pixels. Use [PaddingValues.invoke] factories for
 * symmetric/uniform construction.
 *
 * `start`/`end` correspond to left/right in LTR (we only support LTR for now).
 */
data class PaddingValues(
    val start: Int,
    val top: Int,
    val end: Int,
    val bottom: Int,
) {
    val horizontal: Int get() = start + end
    val vertical: Int get() = top + bottom

    companion object {
        val Zero = PaddingValues(0, 0, 0, 0)

        operator fun invoke(all: Int) = PaddingValues(all, all, all, all)

        operator fun invoke(horizontal: Int, vertical: Int) =
            PaddingValues(horizontal, vertical, horizontal, vertical)
    }
}
