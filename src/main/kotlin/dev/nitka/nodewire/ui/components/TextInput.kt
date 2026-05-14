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
 * Compact text field, Blender / UE5 -style: tight padding, no chunky
 * border at rest — just a subtle surface fill, accent edge when focused.
 *
 * Focus state lives in the global [LocalKeyFocus] controller (which is
 * Compose-state-backed) and is read directly here, so external focus
 * loss (another input clicked, or any background-click clearing focus)
 * shows up in this widget's recomposition without a local mirror.
 *
 * Caller owns [value] — filter/clamp/parse there.
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
    // rememberUpdatedState so the long-lived KeyHandler closure always
    // sees the latest value/callbacks without being re-created (and re-
    // registered) every composition.
    val valueState = rememberUpdatedState(value)
    val onChangeState = rememberUpdatedState(onValueChange)
    val onSubmitState = rememberUpdatedState(onSubmit)

    val handler = remember {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean = when (event) {
                is KeyEvent.Char -> {
                    val cp = event.codePoint
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
                        focus?.release(this)
                        true
                    }
                    KEY_ESCAPE -> {
                        focus?.release(this)
                        true
                    }
                    else -> false
                }
                else -> false
            }
        }
    }
    val focused = focus?.isFocused(handler) == true

    // Release on unmount so navigating away cleans up the focus slot.
    DisposableEffect(handler) {
        onDispose { focus?.release(handler) }
    }

    Box(
        modifier = modifier
            .background(
                if (focused) NwTheme.colors.surfacePressed else NwTheme.colors.surfaceHover,
                NwTheme.shapes.medium,
            )
            // Only show a border (accent) when focused; resting state stays
            // flat to match Blender / UE5's compact form density.
            .let { m ->
                if (focused) m.border(BorderStroke(1, NwTheme.colors.accent), NwTheme.shapes.medium)
                else m
            }
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) {
                    focus?.request(handler)
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
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceDisabled),
                )
            } else {
                Text(value, style = NwTheme.typography.caption)
            }
            if (focused) {
                Box(modifier = Modifier.size(1, 7).background(NwTheme.colors.accent))
            }
        }
    }
}

private const val KEY_ENTER = 257
private const val KEY_ESCAPE = 256
private const val KEY_BACKSPACE = 259
private const val KEY_NUMPAD_ENTER = 335
