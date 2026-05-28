package dev.nitka.nodewire.ui.input

import androidx.compose.runtime.compositionLocalOf

/**
 * Receiver of keyboard input. Implemented by anything that wants raw key
 * / char events while focused — typically [TextInput].
 *
 * Return `true` from [handle] to consume the event; the framework will
 * stop further routing and return `true` from MC's `keyPressed` /
 * `charTyped` so the parent screen doesn't act on the keystroke (e.g. so
 * pressing `e` while typing doesn't open the inventory).
 */
interface KeyHandler {
    fun handle(event: KeyEvent): Boolean

    /**
     * Called by the framework when this handler loses key focus due to an
     * outside press (not a self-initiated release). Lets a focused field
     * resolve (commit / dismiss) on click-away — without it the focus slot
     * drops silently and an autoFocus field would re-acquire next frame as a
     * caret-less ghost. Default no-op.
     */
    fun onFocusLost() {}
}

/**
 * Single-slot focus controller. Whoever currently holds focus receives
 * every key event until they release. Click-elsewhere → release is
 * implemented at the framework boundary (`NwUiOwner.dispatchPointer`)
 * so individual TextInputs don't have to track it.
 */
interface KeyFocusController {
    fun request(handler: KeyHandler)
    fun release(handler: KeyHandler)
    /** True iff [handler] is currently the focused one. */
    fun isFocused(handler: KeyHandler): Boolean
}

val LocalKeyFocus = compositionLocalOf<KeyFocusController?> { null }
