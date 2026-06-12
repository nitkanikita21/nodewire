package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.PinEditor
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.ContextMenu
import dev.nitka.nodewire.ui.components.ContextMenuItem
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
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
    // Buffer is NOT keyed on the value string (that collided across pins of
    // equal value); slot identity comes from key(pin.id) in NodeCard. Re-prime
    // from `current` via an effect so an external change (undo, pin-default
    // set elsewhere) updates the field without resurrecting a stale buffer.
    var text by remember { mutableStateOf(initial) }
    LaunchedEffect(current) { text = initial }
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
        style = ButtonDefaults.compact(),
    ) {
        Text(if (v) "✓" else " ", style = NwTheme.typography.caption)
    }
}

@Composable
private fun TextEditor(current: PinValue, onChange: (PinValue) -> Unit) {
    val initial = (current as? PinValue.Str)?.value ?: ""
    var text by remember { mutableStateOf(initial) }
    LaunchedEffect(current) { text = initial }
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
        is PinValue.Vec2 -> doubleArrayOf(current.x, current.y, 0.0, 0.0)
        is PinValue.Vec3 -> doubleArrayOf(current.x, current.y, current.z, 0.0)
        is PinValue.Quat -> doubleArrayOf(current.x, current.y, current.z, current.w)
        else -> doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    }
    val componentCount = when (pinType) {
        PinType.VEC2 -> 2
        PinType.VEC3 -> 3
        PinType.QUAT -> 4
        else -> 0
    }
    // Not keyed on the float values (two pins both (0,0,0) shared a slot's
    // stale buffer); slot identity is key(pin.id) in NodeCard. All four
    // buffers re-prime atomically from `current` on an external change.
    var x by remember { mutableStateOf(initial[0].toString()) }
    var y by remember { mutableStateOf(initial[1].toString()) }
    var z by remember { mutableStateOf(initial[2].toString()) }
    var w by remember { mutableStateOf(initial[3].toString()) }
    LaunchedEffect(current) {
        x = initial[0].toString(); y = initial[1].toString()
        z = initial[2].toString(); w = initial[3].toString()
    }

    fun commit() {
        val xf = x.toDoubleOrNull() ?: 0.0
        val yf = y.toDoubleOrNull() ?: 0.0
        val zf = z.toDoubleOrNull() ?: 0.0
        val wf = w.toDoubleOrNull() ?: 0.0
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
 * Anchored dropdown for Enum editors. Click the button → opens a
 * [ContextMenu] anchored below the button with every option as an
 * action row. Click outside or click an option to dismiss.
 */
@Composable
private fun EnumEditor(
    options: List<String>,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val cur = (current as? PinValue.Str)?.value ?: options.firstOrNull().orEmpty()
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Button(
        onClick = { if (options.isNotEmpty()) open = !open },
        modifier = Modifier.onPositioned { anchor = it },
        style = ButtonDefaults.compact(),
    ) {
        Text(cur.ifEmpty { "—" }, style = NwTheme.typography.caption)
    }
    val anchorCoords = anchor
    if (open && anchorCoords != null) {
        ContextMenu(
            items = options.map { opt ->
                ContextMenuItem.Action(opt) {
                    onChange(PinValue.Str(opt))
                    open = false
                }
            },
            position = PopupPosition.Anchored(
                anchor = anchorCoords,
                placement = PopupPlacement.Below,
                gap = NwTheme.dimens.space4,
            ),
            onDismiss = { open = false },
        )
    }
}
