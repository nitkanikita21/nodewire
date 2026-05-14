package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.components.Checkbox
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Per-NodeType configuration widgets. The factories below are wired into
 * [NodeType.configContent] from `StockNodeTypes` so each node type can
 * decide what config UI (if any) appears inside its card.
 *
 * Pattern: each composable owns a `remember`d state primed from
 * [Node.config], with the change handler writing back to `node.config`
 * AND updating local state so Compose re-renders. The config tag is the
 * source of truth on save; local state is only for in-session reactivity.
 */
object NodeConfigContent {

    /** INT_CONST: numeric text input. Non-numeric chars cause the value to stay at last valid; emptied → 0. */
    val IntConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("value").toString()) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "0",
            onValueChange = { new ->
                // Accept "", "-", and any prefix of an int. Parse and write
                // through whatever's numerically valid; the text shown stays
                // exactly what the user typed (so they can type "-" then "3").
                val filtered = new.filterIndexed { i, c ->
                    c.isDigit() || (c == '-' && i == 0)
                }
                text = filtered
                val v = filtered.toIntOrNull() ?: 0
                node.config.putInt("value", v)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** FLOAT_CONST: numeric text input accepting optional sign and one dot. */
    val FloatConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getFloat("value").toString()) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "0.0",
            onValueChange = { new ->
                // Accept digits, optional leading '-', and at most one '.'.
                var dotSeen = false
                val filtered = buildString {
                    for ((i, c) in new.withIndex()) {
                        when {
                            c.isDigit() -> append(c)
                            c == '-' && i == 0 -> append(c)
                            c == '.' && !dotSeen -> { append(c); dotSeen = true }
                        }
                    }
                }
                text = filtered
                val v = filtered.toFloatOrNull() ?: 0f
                node.config.putFloat("value", v)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** STRING_CONST: plain text input, no filtering. */
    val StringConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getString("value")) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "(empty)",
            onValueChange = { new ->
                text = new
                node.config.putString("value", new)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** DELAY: number of ticks the input is held back by. */
    val DelayTicks: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("delay").toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(
                "Ticks",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceIn(0, 200)
                    node.config.putInt("delay", v)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    /** TIMER: integer period in ticks. Same widget shape as IntConst. */
    val TimerPeriod: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("period").toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(
                "Period",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceAtLeast(1)
                    node.config.putInt("period", v)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    private val FACES = listOf("down", "up", "north", "south", "west", "east")

    /** SideInput / SideOutput: cycle-button picking which world face this node binds to. */
    val SideFace: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var face by remember { mutableStateOf(node.config.getString("face").ifEmpty { "north" }) }
        CycleButton(
            label = "Face",
            value = face,
            onCycle = {
                val idx = FACES.indexOf(face).coerceAtLeast(0)
                val next = FACES[(idx + 1) % FACES.size]
                face = next
                node.config.putString("face", next)
                editor?.bumpGraphVersion()
            },
        )
    }

    /**
     * ChannelInput / ChannelOutput: name + type pickers. Changing type
     * rebuilds the node's pin list and auto-disconnects any incompatible
     * edges (see [EditorState.changeChannelType]).
     */
    val ChannelEndpoint: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var name by remember { mutableStateOf(node.config.getString("name")) }
        val current = PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name })
        val channelTypes = listOf(
            PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.REDSTONE,
            PinType.STRING, PinType.VEC2, PinType.VEC3, PinType.QUAT,
        )
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            Row(
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                Text(
                    "Name",
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                )
                TextInput(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    placeholder = "(unnamed)",
                    onValueChange = { new ->
                        name = new
                        node.config.putString("name", new)
                        editor?.bumpGraphVersion()
                    },
                )
            }
            CycleButton(
                label = "Type",
                value = current.name.lowercase(),
                onCycle = {
                    val idx = channelTypes.indexOf(current).coerceAtLeast(0)
                    val next = channelTypes[(idx + 1) % channelTypes.size]
                    editor?.changeChannelType(node, next)
                },
            )
        }
    }

    /**
     * ConvertToRedstone: source type + mode pickers. Mode list depends on
     * source type — only valid combos are reachable. Threshold/scale/level
     * config fields shown when the mode uses them.
     */
    val ConvertToRedstone: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val sourceType = PinType.fromName(node.config.getString("sourceType").ifEmpty { PinType.INT.name })
        val mode = node.config.getString("mode").ifEmpty { defaultModeFor(sourceType) }
        val sourceTypes = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            CycleButton(
                label = "From",
                value = sourceType.name.lowercase(),
                onCycle = {
                    val idx = sourceTypes.indexOf(sourceType).coerceAtLeast(0)
                    val next = sourceTypes[(idx + 1) % sourceTypes.size]
                    // Reset to default mode for the new source type to avoid
                    // landing on an incompatible mode (e.g. switching to BOOL
                    // while mode is "modulo").
                    node.config.putString("sourceType", next.name)
                    node.config.putString("mode", defaultModeFor(next))
                    editor?.changeConverterInput(node, next)
                },
            )
            val modes = modesFor(sourceType)
            CycleButton(
                label = "Mode",
                value = mode,
                onCycle = {
                    val idx = modes.indexOf(mode).coerceAtLeast(0)
                    node.config.putString("mode", modes[(idx + 1) % modes.size])
                    editor?.bumpGraphVersion()
                },
            )
            ModeParams(node, sourceType, mode, editor)
        }
    }

    private fun defaultModeFor(t: PinType) = when (t) {
        PinType.INT -> "clamp"
        PinType.FLOAT -> "scaled"
        PinType.BOOL -> "hi"
        else -> "clamp"
    }

    private fun modesFor(t: PinType): List<String> = when (t) {
        PinType.INT -> listOf("clamp", "modulo", "threshold", "scaled")
        PinType.FLOAT -> listOf("threshold", "scaled")
        PinType.BOOL -> listOf("hi", "level")
        else -> listOf("clamp")
    }

    @Composable
    private fun ModeParams(node: Node, sourceType: PinType, mode: String, editor: EditorState?) {
        when {
            sourceType == PinType.INT && mode == "threshold" ->
                IntField(node, "threshold", "Threshold", editor)
            sourceType == PinType.INT && mode == "scaled" -> {
                IntField(node, "min", "Min", editor)
                IntField(node, "max", "Max", editor)
            }
            sourceType == PinType.FLOAT && mode == "threshold" ->
                FloatField(node, "threshold", "Threshold", editor)
            sourceType == PinType.FLOAT && mode == "scaled" -> {
                FloatField(node, "min", "Min", editor)
                FloatField(node, "max", "Max", editor)
            }
            sourceType == PinType.BOOL && mode == "level" ->
                IntField(node, "level", "Level (0..15)", editor)
        }
    }

    @Composable
    private fun IntField(node: Node, key: String, label: String, editor: EditorState?) {
        var text by remember(key) { mutableStateOf(node.config.getInt(key).toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(label, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val f = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                    text = f
                    node.config.putInt(key, f.toIntOrNull() ?: 0)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    @Composable
    private fun FloatField(node: Node, key: String, label: String, editor: EditorState?) {
        var text by remember(key) { mutableStateOf(node.config.getFloat(key).toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(label, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    var dot = false
                    val f = buildString {
                        new.forEachIndexed { i, c ->
                            when {
                                c.isDigit() -> append(c)
                                c == '-' && i == 0 -> append(c)
                                c == '.' && !dot -> { append(c); dot = true }
                            }
                        }
                    }
                    text = f
                    node.config.putFloat(key, f.toFloatOrNull() ?: 0f)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    /**
     * Cycle-button: click advances [value] to next in a cycle. Stand-in
     * for a real Select dropdown until we have one — fine for short enums
     * (faces = 6, types = 8, modes ≤ 4) where chained clicks are quick.
     */
    @Composable
    private fun CycleButton(label: String, value: String, onCycle: () -> Unit) {
        var hovered by remember { mutableStateOf(false) }
        val bg = if (hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, NwTheme.shapes.medium)
                .border(BorderStroke(1, NwTheme.colors.border), NwTheme.shapes.medium)
                .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
                .onHover { hovered = it }
                .pointerInput { ev, _, _ ->
                    if (ev is PointerEvent.Press) { onCycle(); true } else false
                },
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
            Text(value, style = NwTheme.typography.caption)
        }
    }

    /** BOOL_CONST: single checkbox bound to `config.value`. */
    val BoolConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var value by remember { mutableStateOf(node.config.getBoolean("value")) }
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Checkbox(
                checked = value,
                onCheckedChange = { v ->
                    value = v
                    node.config.putBoolean("value", v)
                    editor?.bumpGraphVersion()
                },
            )
            Text(
                if (value) "true" else "false",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }
}
