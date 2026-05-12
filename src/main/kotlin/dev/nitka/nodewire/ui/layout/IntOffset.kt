package dev.nitka.nodewire.ui.layout

@JvmInline
value class IntOffset internal constructor(private val packed: Long) {
    val x: Int get() = (packed ushr 32).toInt()
    val y: Int get() = packed.toInt()

    operator fun component1() = x
    operator fun component2() = y

    operator fun plus(other: IntOffset) = IntOffset(x + other.x, y + other.y)
    operator fun minus(other: IntOffset) = IntOffset(x - other.x, y - other.y)

    override fun toString() = "IntOffset($x, $y)"

    companion object {
        val Zero = IntOffset(0, 0)
    }
}

fun IntOffset(x: Int, y: Int): IntOffset =
    IntOffset((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
