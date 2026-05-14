package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.KeyHandler
import dev.nitka.nodewire.ui.input.LocalKeyFocus
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Single-line text input. Click to focus, Esc or Enter to release; while
 * focused, printable chars append to [value] and Backspace removes the
 * last character.
 *
 * State is hoisted — caller owns [value] and decides how to react to
 * [onValueChange]. That makes it trivial to validate / clamp at the call
 * site (e.g. an Int config widget parses + clamps before storing).
 *
 * Cursor: a single accent-colored block painted after the text. No
 * blinking yet — adding a clock would force a frame request every tick
 * which we don't need for MVP.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSubmit: () -> Unit = {},
) {
    val focus = LocalKeyFocus.current
    var focused by remember { mutableStateOf(false) }
    // Capture the latest value/callbacks as state so the long-lived
    // KeyHandler closure always reads up-to-date values without being
    // recreated (and re-registered with the focus controller) every
    // composition.
    val valueState = rememberUpdatedState(value)
    val onChangeState = rememberUpdatedState(onValueChange)
    val onSubmitState = rememberUpdatedState(onSubmit)

    val handler = remember {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean = when (event) {
                is KeyEvent.Char -> {
                    val cp = event.codePoint
                    // Filter ISO control chars (also covers backspace=8,
                    // tab=9, newline=10 which we handle in keyPressed if
                    // at all). We accept everything else as text input.
                    if (cp >= 0x20 && cp != 0x7F) {
                        val ch = Character.toChars(cp).concatToString()
                        onChangeState.value(valueState.value + ch)
                        true
                    } else false
                }
                is KeyEvent.Press -> when (event.keyCode) {
                    KEY_BACKSPACE -> {
                        if (valueState.value.isNotEmpty()) {
                            onChangeState.value(valueState.value.dropLast(1))
                        }
                        true
                    }
                    KEY_ENTER, KEY_NUMPAD_ENTER -> {
                        onSubmitState.value()
                        true
                    }
                    KEY_ESCAPE -> true
                    else -> false
                }
                else -> false
            }
        }
    }

    DisposableEffect(focused) {
        if (focused) focus?.request(handler)
        onDispose { focus?.release(handler) }
    }
    // Sync external focus loss (e.g. user clicked elsewhere → owner cleared
    // focus) back to our local state on the next frame.
    if (focused && focus != null && !focus.isFocused(handler)) {
        focused = false
    }

    Box(
        modifier = modifier
            .background(
                if (focused) NwTheme.colors.surfaceHover else NwTheme.colors.surface,
                NwTheme.shapes.medium,
            )
            .border(
                BorderStroke(
                    1,
                    if (focused) NwTheme.colors.accent else NwTheme.colors.border,
                ),
                NwTheme.shapes.medium,
            )
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) {
                    focused = true
                    true
                } else false
            },
    ) {
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty() && !focused) {
                Text(
                    placeholder,
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                )
            } else {
                Text(value, style = NwTheme.typography.caption)
            }
            if (focused) {
                // Solid block caret — readable at MC font size without
                // animation overhead. ~5px tall matches caption line.
                Box(
                    modifier = Modifier
                        .size(1, 7)
                        .background(NwTheme.colors.accent),
                )
            }
        }
    }
}

private const val KEY_ENTER = 257
private const val KEY_ESCAPE = 256
private const val KEY_BACKSPACE = 259
private const val KEY_NUMPAD_ENTER = 335
