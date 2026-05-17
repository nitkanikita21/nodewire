package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.KeyHandler
import dev.nitka.nodewire.ui.input.LocalKeyFocus
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.text.KeyBinding
import dev.nitka.nodewire.ui.input.text.TextFieldKeyBindings
import dev.nitka.nodewire.ui.input.text.TextFieldRenderer
import dev.nitka.nodewire.ui.input.text.TextFieldState
import dev.nitka.nodewire.ui.input.text.TextFieldStateHolder
import dev.nitka.nodewire.ui.input.text.TextRange
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.onSizeChanged
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

/**
 * Compact text field with Jewel-level editing UX: blinking caret,
 * selection (Shift-extend, word jump, drag-select, double/triple click),
 * clipboard (Ctrl+A/C/X/V), undo/redo (Ctrl+Z/Y), submit (Enter),
 * cancel (Esc).
 *
 * Visual style preserved from the prior implementation — surface fill +
 * focus-only accent border.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
    keyBindings: List<KeyBinding> = TextFieldKeyBindings.DEFAULT,
) {
    val focusController = LocalKeyFocus.current
    val font = Minecraft.getInstance().font

    val holder = remember {
        TextFieldStateHolder(
            initial = TextFieldState(value, TextRange.caret(value.length)),
            clipboardGet = { Minecraft.getInstance().keyboardHandler.clipboard ?: "" },
            clipboardSet = { Minecraft.getInstance().keyboardHandler.clipboard = it },
            nowMillis = { Util.getMillis() },
        )
    }

    // Text is rendered at caption scale so the field matches Select's
    // visual size (Select uses caption typography). Holder font metrics
    // must follow suit so caret / selection / scroll math is in the same
    // screen-pixel units the renderer paints in.
    val textScale = NwTheme.typography.caption.scale
    val scaledLineHeight = (font.lineHeight * textScale).toInt().coerceAtLeast(1)
    holder.fontWidthOf = { (font.width(it) * textScale).toInt() }
    holder.fontHeightPx = scaledLineHeight
    holder.paddingLeftPx = 0

    val cb = rememberUpdatedState(onValueChange)
    val onSubmitState = rememberUpdatedState(onSubmit)

    // Sync external value → holder.
    LaunchedEffect(value) { if (holder.state.text != value) holder.replaceText(value) }
    // Internal edits → onValueChange (skip the echo from a value-driven replaceText).
    LaunchedEffect(holder) {
        snapshotFlow { holder.state.text }.collect { if (it != value) cb.value(it) }
    }

    val handler = remember(holder, keyBindings) {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean {
                if (!enabled) return false
                return when (event) {
                    is KeyEvent.Char -> {
                        // Ignore chars with Ctrl/Alt/Super so shortcuts win.
                        val mods = event.modifiers
                        val ctrlAltSuper = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER
                        if (mods and ctrlAltSuper != 0) false
                        else { holder.insertChar(event.codePoint); true }
                    }
                    is KeyEvent.Press -> {
                        TextFieldKeyBindings.match(keyBindings, event)?.action?.invoke(holder) ?: false
                    }
                    else -> false
                }
            }
        }
    }
    val focused = focusController?.isFocused(handler) == true

    holder.onSubmit = { onSubmitState.value(); focusController?.release(handler) }
    holder.onReleaseFocus = { focusController?.release(handler) }

    DisposableEffect(handler) { onDispose { focusController?.release(handler) } }

    // Caret-blink ticker — drives recomposition only while focused.
    var blinkTick by remember { mutableStateOf(0) }
    LaunchedEffect(focused) {
        if (focused) while (true) { delay(50); blinkTick++ }
    }
    val blinkOn = ((Util.getMillis() % 1000L) < 530L)
    // Force read of blinkTick so the composer treats it as a dependency.
    @Suppress("UNUSED_EXPRESSION") blinkTick

    // Intrinsic height: scaled font line + 2px vertical padding. Matches
    // Select (Text(caption) inside a Row with vertical=1 padding) so both
    // controls have the same row height inside node config sections.
    val rowHeight = scaledLineHeight + 2
    var hovered by remember { mutableStateOf(false) }
    // Background mirrors Select: surfaceHover at rest, surfacePressed when
    // hovered or focused. No border — Select doesn't have one either; the
    // distinct fill against the (typically `surface`) parent is the field
    // affordance.
    val bg = when {
        focused -> NwTheme.colors.surfacePressed
        hovered -> NwTheme.colors.surfacePressed
        else -> NwTheme.colors.surfaceHover
    }

    Layout(
        modifier = modifier
            .height(rowHeight)
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            .onHover { hovered = it }
            .onSizeChanged { size -> holder.visibleWidthPx = size.width }
            .pointerInput { ev, localX, _ ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        val shift = Screen.hasShiftDown()
                        holder.mousePress(localX, shift, Util.getMillis())
                        true
                    }
                    is PointerEvent.Drag -> {
                        if (ev.button == 0) { holder.mouseDrag(localX); true } else false
                    }
                    else -> false
                }
            },
        renderer = TextFieldRenderer(
            holder = holder,
            font = font,
            textColor = NwTheme.colors.onSurface,
            placeholderColor = NwTheme.colors.onSurfaceDisabled,
            selectionColor = NwTheme.colors.accent.copy(alpha = 0.4f),
            caretColor = NwTheme.colors.accent,
            placeholder = placeholder,
            isFocused = { focused },
            blinkOn = { blinkOn },
            textScale = textScale,
        ),
    )
}
