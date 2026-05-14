package dev.nitka.nodewire.ui.feedback

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-screen toast queue. Holds the currently-visible [Toast]s in
 * insertion order (newest last so it renders at the top of the stack).
 *
 * Auto-dismiss is wired by [ToastHost] via a per-toast `LaunchedEffect`;
 * callers don't have to think about timing.
 */
class ToastManager {
    private val ids = AtomicLong(0)
    val toasts = mutableStateListOf<Toast>()

    fun show(
        message: String,
        variant: ToastVariant = ToastVariant.Info,
        durationMs: Long = DEFAULT_DURATION_MS,
    ): Long {
        val id = ids.incrementAndGet()
        toasts.add(Toast(id, message, variant, durationMs))
        return id
    }

    fun success(message: String, durationMs: Long = DEFAULT_DURATION_MS) =
        show(message, ToastVariant.Success, durationMs)

    fun info(message: String, durationMs: Long = DEFAULT_DURATION_MS) =
        show(message, ToastVariant.Info, durationMs)

    fun warning(message: String, durationMs: Long = DEFAULT_DURATION_MS) =
        show(message, ToastVariant.Warning, durationMs)

    fun danger(message: String, durationMs: Long = DEFAULT_DURATION_MS) =
        show(message, ToastVariant.Danger, durationMs)

    fun dismiss(id: Long) {
        toasts.removeAll { it.id == id }
    }

    companion object {
        const val DEFAULT_DURATION_MS = 3000L
    }
}

/**
 * Access to the surrounding [ToastManager]. Null outside a [ToastHost].
 *
 * `rememberToastManager` is the conventional access pattern from
 * composables that need to emit toasts.
 */
val LocalToastManager = compositionLocalOf<ToastManager?> { null }
