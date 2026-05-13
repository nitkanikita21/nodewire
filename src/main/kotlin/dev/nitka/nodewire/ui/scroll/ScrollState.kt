package dev.nitka.nodewire.ui.scroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Live scroll position with smooth animation.
 *
 *   * [target] is where the scroll position is headed — updated immediately
 *     when the user scrolls via [scrollBy] / [scrollTo].
 *   * [value] is the on-screen position — eased toward [target] one frame
 *     at a time by [advance]. PaintWalk reads `value` (not `target`) so
 *     the visual scroll is always smooth even with discrete wheel notches.
 *
 * `maxValue` is recomputed each frame by [NwUiOwner]'s post-layout walk
 * (= `contentSize - viewportSize`, clamped to ≥ 0). Treat as observation-
 * only from user code.
 */
class ScrollState(initial: Int = 0) {
    private var _value by mutableStateOf(initial.coerceAtLeast(0))
    private var _target by mutableStateOf(initial.coerceAtLeast(0))
    private var _maxValue by mutableStateOf(Int.MAX_VALUE)

    val value: Int get() = _value
    val target: Int get() = _target

    var maxValue: Int
        get() = _maxValue
        internal set(newMax) {
            _maxValue = newMax
            if (_target > newMax) _target = newMax
            if (_value > newMax) _value = newMax
        }

    fun scrollTo(t: Int) {
        _target = t.coerceIn(0, _maxValue)
    }

    fun scrollBy(delta: Int) = scrollTo(_target + delta)

    /**
     * Step [value] one frame closer to [target]. Exponential ease: move
     * `max(1, |diff| / 4)` pixels per frame. Reaches the target in
     * ~log4(distance) frames + tail of 1px/frame — feels responsive without
     * being instant. Called by NwUiOwner during the post-layout walk.
     */
    internal fun advance() {
        if (_value == _target) return
        val diff = _target - _value
        val step = (abs(diff) / 4).coerceAtLeast(1).coerceAtMost(abs(diff))
        _value += if (diff > 0) step else -step
    }

    val isAtTop: Boolean get() = _target == 0
    val isAtBottom: Boolean get() = _target >= _maxValue
}

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = remember { ScrollState(initial) }
