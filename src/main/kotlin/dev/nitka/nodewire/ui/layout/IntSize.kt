package dev.nitka.nodewire.ui.layout

/**
 * Two ints packed in a `Long` (high 32 = width, low 32 = height).
 * Avoids allocation when passing dimensions around the layout pipeline.
 */
@JvmInline
value class IntSize internal constructor(private val packed: Long) {
    val width: Int get() = (packed ushr 32).toInt()
    val height: Int get() = packed.toInt()

    operator fun component1() = width
    operator fun component2() = height

    override fun toString() = "IntSize($width, $height)"

    companion object {
        val Zero = IntSize(0, 0)
    }
}

fun IntSize(width: Int, height: Int): IntSize =
    IntSize((width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL))
