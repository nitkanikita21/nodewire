package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.PinEditor
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Inline editor for an unconnected input pin. Dispatches to a per-
 * variant compose function based on the [PinEditor] spec. [current]
 * is the current pin-default value (or [PinValue.default] if none has
 * been set yet) and [onChange] persists a new value to the node.
 *
 * `PinEditor.None` and `PinEditor.Slider` are handled by the caller
 * (caller decides not to render None; Slider is out of scope).
 */
@Composable
fun PinDefaultEditor(
    editor: PinEditor,
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    when (editor) {
        PinEditor.Numeric -> NumericEditor(pinType, current, onChange)
        PinEditor.Checkbox -> CheckboxEditor(current, onChange)
        PinEditor.Text -> TextEditor(current, onChange)
        PinEditor.Vector -> VectorEditor(pinType, current, onChange)
        is PinEditor.Enum -> EnumEditor(editor.options, current, onChange)
        is PinEditor.Slider, PinEditor.None -> Unit
    }
}

@Composable
private fun NumericEditor(
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val initial = when (current) {
        is PinValue.Int -> current.value.toString()
        is PinValue.Float -> current.value.toString()
        is PinValue.Redstone -> current.value.toString()
        else -> "0"
    }
    var text by remember(initial) { mutableStateOf(initial) }
    TextInput(
        value = text,
        modifier = Modifier.width(50),
        onValueChange = { text = it },
        onSubmit = {
            val parsed: PinValue = when (pinType) {
                PinType.INT -> PinValue.Int(text.toIntOrNull() ?: 0)
                PinType.FLOAT -> PinValue.Float(text.toFloatOrNull() ?: 0f)
                PinType.REDSTONE -> PinValue.Redstone(
                    (text.toIntOrNull() ?: 0).coerceIn(0, 15)
                )
                else -> return@TextInput
            }
            onChange(parsed)
        },
    )
}

@Composable
private fun CheckboxEditor(current: PinValue, onChange: (PinValue) -> Unit) {
    val v = (current as? PinValue.Bool)?.value ?: false
    Button(
        onClick = { onChange(PinValue.Bool(!v)) },
        modifier = Modifier.width(14),
    ) {
        Text(if (v) "✓" else " ", style = NwTheme.typography.caption)
    }
}

@Composable
private fun TextEditor(current: PinValue, onChange: (PinValue) -> Unit) {
    val initial = (current as? PinValue.Str)?.value ?: ""
    var text by remember(initial) { mutableStateOf(initial) }
    TextInput(
        value = text,
        modifier = Modifier.width(100),
        onValueChange = { text = it },
        onSubmit = { onChange(PinValue.Str(text)) },
    )
}

@Composable
private fun VectorEditor(
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val initial = when (current) {
        is PinValue.Vec2 -> floatArrayOf(current.x, current.y, 0f, 0f)
        is PinValue.Vec3 -> floatArrayOf(current.x, current.y, current.z, 0f)
        is PinValue.Quat -> floatArrayOf(current.x, current.y, current.z, current.w)
        else -> floatArrayOf(0f, 0f, 0f, 0f)
    }
    val componentCount = when (pinType) {
        PinType.VEC2 -> 2
        PinType.VEC3 -> 3
        PinType.QUAT -> 4
        else -> 0
    }
    var x by remember(initial[0]) { mutableStateOf(initial[0].toString()) }
    var y by remember(initial[1]) { mutableStateOf(initial[1].toString()) }
    var z by remember(initial[2]) { mutableStateOf(initial[2].toString()) }
    var w by remember(initial[3]) { mutableStateOf(initial[3].toString()) }

    fun commit() {
        val xf = x.toFloatOrNull() ?: 0f
        val yf = y.toFloatOrNull() ?: 0f
        val zf = z.toFloatOrNull() ?: 0f
        val wf = w.toFloatOrNull() ?: 0f
        val value: PinValue = when (pinType) {
            PinType.VEC2 -> PinValue.Vec2(xf, yf)
            PinType.VEC3 -> PinValue.Vec3(xf, yf, zf)
            PinType.QUAT -> PinValue.Quat(xf, yf, zf, wf)
            else -> return
        }
        onChange(value)
    }

    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(2),
    ) {
        TextInput(value = x, modifier = Modifier.width(30), onValueChange = { x = it }, onSubmit = { commit() })
        TextInput(value = y, modifier = Modifier.width(30), onValueChange = { y = it }, onSubmit = { commit() })
        if (componentCount >= 3) {
            TextInput(value = z, modifier = Modifier.width(30), onValueChange = { z = it }, onSubmit = { commit() })
        }
        if (componentCount >= 4) {
            TextInput(value = w, modifier = Modifier.width(30), onValueChange = { w = it }, onSubmit = { commit() })
        }
    }
}

/**
 * Cycle-on-click fallback for Enum editors. The project's [ContextMenu]
 * API requires explicit screen coordinates (no `PopupPosition.Auto`) so
 * we punt on the anchored dropdown for now — click the button to advance
 * to the next option. A proper anchored picker is a follow-up.
 */
@Composable
private fun EnumEditor(
    options: List<String>,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val cur = (current as? PinValue.Str)?.value ?: options.firstOrNull().orEmpty()
    Button(
        onClick = {
            if (options.isEmpty()) return@Button
            val idx = options.indexOf(cur).coerceAtLeast(0)
            val next = options[(idx + 1) % options.size]
            onChange(PinValue.Str(next))
        },
    ) {
        Text(cur.ifEmpty { "—" }, style = NwTheme.typography.caption)
    }
}
