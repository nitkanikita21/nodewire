package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Checkbox
import dev.nitka.nodewire.ui.components.Select
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.integration.aeronautics.AeroBlockKind
import dev.nitka.nodewire.integration.aeronautics.AeroChannel
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.nbt.NbtOps

/**
 * Per-NodeType configuration widgets. The factories below are wired into
 * [NodeType.configContent] from `StockNodeTypes` so each node type can
 * decide what config UI (if any) appears inside its card.
 *
 * Pattern: each composable owns a `remember`d state primed from
 * [Node.config], with the change handler writing back via [EditorState.updateNode]
 * AND updating local state so Compose re-renders. The config tag is the
 * source of truth on save; local state is only for in-session reactivity.
 */
object NodeConfigContent {

    /** Constant (INT slot): numeric text input writing to `config.int`. */
    @Composable
    private fun ConstantBodyInt(node: Node, editor: EditorState?) {
        var text by remember(node.config.getInt("int")) { mutableStateOf(node.config.getInt("int").toString()) }
        LabeledRow("Value") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                placeholder = "0",
                onValueChange = { new ->
                    val filtered = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                    text = filtered
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putInt("int", filtered.toIntOrNull() ?: 0)
                        })
                    }
                },
            )
        }
    }

    /** Constant (FLOAT slot): numeric text input writing to `config.float`. */
    @Composable
    private fun ConstantBodyFloat(node: Node, editor: EditorState?) {
        var text by remember(node.config.getFloat("float")) { mutableStateOf(node.config.getFloat("float").toString()) }
        LabeledRow("Value") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                placeholder = "0.0",
                onValueChange = { new ->
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
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putFloat("float", filtered.toFloatOrNull() ?: 0f)
                        })
                    }
                },
            )
        }
    }

    /** Constant (STRING slot): plain text input writing to `config.string`. */
    @Composable
    private fun ConstantBodyString(node: Node, editor: EditorState?) {
        var text by remember(node.config.getString("string")) { mutableStateOf(node.config.getString("string")) }
        LabeledRow("Value") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                placeholder = "(empty)",
                onValueChange = { new ->
                    text = new
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putString("string", new)
                        })
                    }
                },
            )
        }
    }

    /** TIMER (legacy — Pulse only): integer period in ticks. */
    val TimerPeriod: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember(node.config.getInt("period")) { mutableStateOf(node.config.getInt("period").toString()) }
        LabeledRow("Period") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { ch -> ch.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceAtLeast(1)
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putInt("period", v)
                        })
                    }
                },
            )
        }
    }

    private val FACES = listOf("down", "up", "north", "south", "west", "east")

    /** SideInput / SideOutput: face picker. */
    val SideFace: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val face = node.config.getString("face").ifEmpty { "north" }
        LabeledRow("Face") {
            Select(
                options = FACES,
                selected = face,
                onSelect = { next ->
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putString("face", next)
                        })
                    }
                },
                label = { it },
            )
        }
    }

    /**
     * ChannelInput / ChannelOutput: name + type pickers. Local Compose
     * state holds the user-visible values so the widget recomposes on
     * cycle/edit without depending on NBT-read reactivity. Mutations are
     * mirrored into [Node.config] for save/load.
     */
    val ChannelEndpoint: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var name by remember(node.config.getString("name")) { mutableStateOf(node.config.getString("name")) }
        val type = PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name })
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Name") {
                TextInput(
                    value = name,
                    placeholder = "channel",
                    onValueChange = { new ->
                        name = new
                        editor?.updateNode(node.id) { n ->
                            n.copy(config = n.config.copy().apply {
                                putString("name", new)
                            })
                        }
                    },
                )
            }
            LabeledRow("Type") {
                Select(
                    options = CHANNEL_TYPES,
                    selected = type,
                    onSelect = { next ->
                        editor?.changeChannelType(node.id, next)
                    },
                    label = { it.name.lowercase() },
                )
            }
        }
    }

    /** Redstone-link frequency: two ghost slots + inline inventory popover. */
    val RedstoneLinkFrequency: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        RedstoneLinkFrequencySlots(node, editor)
    }

    private val CHANNEL_TYPES = listOf(
        PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.REDSTONE,
        PinType.STRING, PinType.VEC2, PinType.VEC3, PinType.QUAT,
    )

    @Composable
    private fun IntField(node: Node, key: String, label: String, editor: EditorState?) {
        var text by remember(key) { mutableStateOf(node.config.getInt(key).toString()) }
        LabeledRow(label) {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val f = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                    text = f
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putInt(key, f.toIntOrNull() ?: 0)
                        })
                    }
                },
            )
        }
    }

    @Composable
    private fun FloatField(node: Node, key: String, label: String, editor: EditorState?) {
        var text by remember(key) { mutableStateOf(node.config.getFloat(key).toString()) }
        LabeledRow(label) {
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
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putFloat(key, f.toFloatOrNull() ?: 0f)
                        })
                    }
                },
            )
        }
    }

    /**
     * Reusable label + control row. Label takes natural width (a few chars
     * + colon); control gets [weight] = 1f so it consumes the remaining
     * space — never overflows the parent card width.
     *
     * The wrapper around [content] is a Column with `Alignment.Stretch` so
     * inner widgets actually fill its cross-axis. A plain Box would have
     * default flex-start alignment and let the widget size to its content,
     * which leaves a Select's chevron stuck right next to the value text
     * instead of pushed to the far edge.
     */
    @Composable
    private fun LabeledRow(label: String, content: @Composable () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(
                "$label:",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Stretch,
            ) {
                content()
            }
        }
    }


    /** Constant (BOOL slot): single checkbox writing to `config.bool`. */
    @Composable
    private fun ConstantBodyBool(node: Node, editor: EditorState?) {
        val value = node.config.getBoolean("bool")
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Checkbox(
                checked = value,
                onCheckedChange = { v ->
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putBoolean("bool", v)
                        })
                    }
                },
            )
            Text(
                if (value) "true" else "false",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }

    /** Constant (VEC2 slot): two float fields writing to `config.x2/y2`. */
    @Composable
    private fun ConstantBodyVec2(node: Node, editor: EditorState?) {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            FloatField(node, "x2", "X", editor)
            FloatField(node, "y2", "Y", editor)
        }
    }

    /** Constant (VEC3 slot): three float fields writing to `config.x/y/z`. */
    @Composable
    private fun ConstantBodyVec3(node: Node, editor: EditorState?) {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            FloatField(node, "x", "X", editor)
            FloatField(node, "y", "Y", editor)
            FloatField(node, "z", "Z", editor)
        }
    }

    /**
     * Constant: type selector + per-type value field. Dispatches to one of
     * the private ConstantBody* helpers based on the selected type.
     */
    val Constant: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val type = PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name })
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Type") {
                Select(
                    options = CONSTANT_TYPES,
                    selected = type,
                    onSelect = { next ->
                        editor?.changeConstantType(node.id, next)
                    },
                    label = { it.name.lowercase() },
                )
            }
            when (type) {
                PinType.BOOL -> ConstantBodyBool(node, editor)
                PinType.INT -> ConstantBodyInt(node, editor)
                PinType.FLOAT -> ConstantBodyFloat(node, editor)
                PinType.STRING -> ConstantBodyString(node, editor)
                PinType.VEC2 -> ConstantBodyVec2(node, editor)
                PinType.VEC3 -> ConstantBodyVec3(node, editor)
                else -> Unit
            }
        }
    }

    private val CONSTANT_TYPES = listOf(
        PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.STRING,
        PinType.VEC2, PinType.VEC3,
    )

    /**
     * Convert: source type + target type + (for REDSTONE pairs) mode selectors.
     * Valid pairs: INT↔FLOAT, INT↔BOOL, INT/FLOAT/BOOL→REDSTONE, REDSTONE→INT/FLOAT/BOOL.
     * Switching source resets target to the first valid option if the current target is
     * no longer valid for the new source. Mode row appears only for REDSTONE pairs.
     */
    val Convert: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val source = PinType.fromName(node.config.getString("sourceType").ifEmpty { PinType.INT.name })
        val target = PinType.fromName(node.config.getString("targetType").ifEmpty { PinType.FLOAT.name })
        val mode = node.config.getString("mode")
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("From") {
                Select(
                    options = CONVERT_SOURCES,
                    selected = source,
                    onSelect = { next ->
                        val validTargets = validTargetsFor(next)
                        val newTarget = if (target in validTargets) target else validTargets.first()
                        editor?.changeConvertTypes(node.id, next, newTarget)
                    },
                    label = { it.name.lowercase() },
                )
            }
            LabeledRow("To") {
                Select(
                    options = validTargetsFor(source),
                    selected = target,
                    onSelect = { next ->
                        editor?.changeConvertTypes(node.id, source, next)
                    },
                    label = { it.name.lowercase() },
                )
            }
            val modes = convertModesFor(source, target)
            if (modes.isNotEmpty()) {
                LabeledRow("Mode") {
                    Select(
                        options = modes,
                        selected = mode.ifEmpty { modes.first() },
                        onSelect = { next ->
                            editor?.changeConvertMode(node.id, next)
                        },
                        label = { it },
                    )
                }
                ConvertModeParams(node, source, target, mode.ifEmpty { modes.first() }, editor)
            }
        }
    }

    @Composable
    private fun ConvertModeParams(node: Node, source: PinType, target: PinType, mode: String, editor: EditorState?) {
        when {
            // *-to-REDSTONE
            source == PinType.INT   && target == PinType.REDSTONE && mode == "threshold" ->
                IntField(node, "threshold", "Threshold", editor)
            source == PinType.INT   && target == PinType.REDSTONE && mode == "scaled" -> {
                IntField(node, "min", "Min", editor); IntField(node, "max", "Max", editor)
            }
            source == PinType.FLOAT && target == PinType.REDSTONE && mode == "threshold" ->
                FloatField(node, "thresholdF", "Threshold", editor)
            source == PinType.FLOAT && target == PinType.REDSTONE && mode == "scaled" -> {
                FloatField(node, "minF", "Min", editor); FloatField(node, "maxF", "Max", editor)
            }
            source == PinType.BOOL  && target == PinType.REDSTONE && mode == "level" ->
                IntField(node, "level", "Level", editor)
            // REDSTONE-to-*
            source == PinType.REDSTONE && target == PinType.INT   && mode == "scaled" -> {
                IntField(node, "min", "Min", editor); IntField(node, "max", "Max", editor)
            }
            source == PinType.REDSTONE && target == PinType.FLOAT && mode == "scaled" -> {
                FloatField(node, "minF", "Min", editor); FloatField(node, "maxF", "Max", editor)
            }
            source == PinType.REDSTONE && target == PinType.BOOL  && mode == "threshold" ->
                IntField(node, "threshold", "Threshold", editor)
        }
    }

    private val CONVERT_SOURCES = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)

    private fun validTargetsFor(src: PinType): List<PinType> = when (src) {
        PinType.INT      -> listOf(PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)
        PinType.FLOAT    -> listOf(PinType.INT, PinType.REDSTONE)
        PinType.BOOL     -> listOf(PinType.INT, PinType.REDSTONE)
        PinType.REDSTONE -> listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)
        else -> emptyList()
    }

    private fun convertModesFor(src: PinType, tgt: PinType): List<String> = when (src to tgt) {
        PinType.INT      to PinType.REDSTONE -> listOf("clamp", "modulo", "threshold", "scaled")
        PinType.FLOAT    to PinType.REDSTONE -> listOf("threshold", "scaled")
        PinType.BOOL     to PinType.REDSTONE -> listOf("hi", "level")
        PinType.REDSTONE to PinType.INT      -> listOf("raw", "scaled")
        PinType.REDSTONE to PinType.FLOAT    -> listOf("normalized", "raw", "scaled")
        PinType.REDSTONE to PinType.BOOL     -> listOf("any", "threshold")
        else -> emptyList()
    }

    /**
     * VecMake: a single Select dim picker. Switching dim adds/removes the
     * z input pin and changes output pin type (handled by [EditorState.changeVecMakeSplitDim]).
     */
    val VecMake: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val dim = node.config.getString("dim").ifEmpty { "VEC2" }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    editor?.changeVecMakeSplitDim(node.id, next)
                },
                label = { it.lowercase() },
            )
        }
    }

    /**
     * AeroInput: endpoint description (read-only, shows blockPos when bound),
     * block-kind Select (all [AeroBlockKind] entries), channel Select (filtered
     * to channels whose [AeroChannel.kind] matches the selected block-kind),
     * and a derived output-pin-type label. Mutations call
     * [EditorState.changeAeroChannel].
     */
    val AeroInput: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current

        val kindName = node.config.getString("blockKind").ifEmpty { AeroBlockKind.SMART_PROPELLER.name }
        val channelName = node.config.getString("channel").ifEmpty { AeroChannel.PROP_ROTATION_SPEED.name }

        val kind = AeroBlockKind.fromName(kindName) ?: AeroBlockKind.SMART_PROPELLER
        val channel = AeroChannel.fromName(channelName) ?: AeroChannel.PROP_ROTATION_SPEED
        val channelOptions = AeroChannel.byKind(kind)

        // Endpoint description — parsed from config; shown read-only.
        val endpointDesc = if (node.config.contains("endpoint")) {
            EndpointRef.CODEC
                .parse(NbtOps.INSTANCE, node.config.getCompound("endpoint"))
                .result().orElse(null)
                ?.payload?.blockPos?.toShortString()
                ?: "(unbound — use Channel Link Tool)"
        } else {
            "(unbound — use Channel Link Tool)"
        }

        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Source") {
                Text(
                    endpointDesc,
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                )
            }
            LabeledRow("Block") {
                Select(
                    options = AeroBlockKind.entries.toList(),
                    selected = kind,
                    onSelect = { nextKind ->
                        val nextChannel = if (channel.kind == nextKind) channel
                            else AeroChannel.byKind(nextKind).first()
                        editor?.changeAeroChannel(node.id, nextKind, nextChannel)
                    },
                    label = { it.displayName },
                )
            }
            LabeledRow("Channel") {
                Select(
                    options = channelOptions,
                    selected = if (channel in channelOptions) channel else channelOptions.first(),
                    onSelect = { nextChannel ->
                        editor?.changeAeroChannel(node.id, kind, nextChannel)
                    },
                    label = { it.displayName },
                )
            }
            LabeledRow("Output") {
                Text(
                    channel.pinType.name.lowercase() +
                        if (channel.writable) "  🔓 rw" else "  🔒 ro",
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                )
            }
        }
    }

    /**
     * ControllerInput: channel Select (all 19 channels grouped via order),
     * outputMode Select (filtered to category-valid options), optional
     * deadzone FloatField + invert checkbox shown depending on category/mode.
     */
    val ControllerInput: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val channel = node.config.getString("channel").ifEmpty {
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.LEFT_STICK.name
        }
        val mode = node.config.getString("outputMode").ifEmpty {
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerOutputMode.VEC2_RAW.name
        }
        val ch = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.fromName(channel)
        val cat = ch.category
        val modeOptions = dev.nitka.nodewire.integration.tweakedcontroller.allowedOutputModes(cat)

        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Channel") {
                Select(
                    options = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.entries.toList(),
                    selected = ch,
                    onSelect = { next ->
                        // mode resets to category default — handled by mutator
                        editor?.changeControllerChannel(node.id, next.name)
                    },
                    label = { it.displayName },
                )
            }
            LabeledRow("Output") {
                Select(
                    options = modeOptions,
                    selected = dev.nitka.nodewire.integration.tweakedcontroller.ControllerOutputMode
                        .entries.firstOrNull { it.name == mode } ?: modeOptions.first(),
                    onSelect = { next ->
                        editor?.changeControllerOutputMode(node.id, next.name)
                    },
                    label = { it.name.lowercase().replace('_', ' ') },
                )
            }
            // Deadzone applies whenever the channel produces a continuous
            // value (composite stick, axis half, or trigger). Buttons
            // skip the deadzone row.
            val showDeadzone = cat != dev.nitka.nodewire.integration.tweakedcontroller
                .ControllerChannelCategory.BUTTON
            if (showDeadzone) {
                FloatField(node, "deadzone", "Deadzone", editor)
            }
        }
    }

    /** VecSplit: same Select as VecMake; output pins reshape. */
    val VecSplit: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val dim = node.config.getString("dim").ifEmpty { "VEC2" }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    editor?.changeVecMakeSplitDim(node.id, next)
                },
                label = { it.lowercase() },
            )
        }
    }

    private val VEC_DIMS = listOf("VEC2", "VEC3")

    /**
     * VecOp: op picker + dim picker. Dim is disabled (locked) when the
     * selected op forces a specific dimension (CROSS=VEC3, ROTATE2D=VEC2,
     * TO_VEC3/TO_VEC2 ignore dim).
     */
    val VecOp: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val op = node.config.getString("op").ifEmpty { "ADD" }
        val dim = node.config.getString("dim").ifEmpty { "VEC2" }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Op") {
                Select(
                    options = VEC_OPS,
                    selected = op,
                    onSelect = { next ->
                        editor?.changeVecOp(node.id, next, dim)
                    },
                    label = { it.lowercase() },
                )
            }
            if (!isVecOpDimLocked(op)) {
                LabeledRow("Dim") {
                    Select(
                        options = VEC_DIMS,
                        selected = dim,
                        onSelect = { next ->
                            editor?.changeVecOp(node.id, op, next)
                        },
                        label = { it.lowercase() },
                    )
                }
            }
        }
    }

    /** Full op catalog, grouped roughly the way users think: binary first,
     *  then unary, scalar-mixed, reductions, dim-specific, conversions. */
    private val VEC_OPS = listOf(
        "ADD", "SUB", "MUL_COMPONENT", "MIN", "MAX",
        "NEGATE", "NORMALIZE", "ABS",
        "SCALE", "CLAMP_MAG", "LERP", "PROJECT", "REFLECT",
        "DOT", "LENGTH", "LENGTH_SQ", "DISTANCE", "ANGLE",
        "CROSS", "ROTATE2D", "TO_VEC3", "TO_VEC2",
    )

    private fun isVecOpDimLocked(op: String): Boolean =
        op == "CROSS" || op == "ROTATE2D" || op == "TO_VEC3" || op == "TO_VEC2"

    /**
     * Switch: row of 2..8 case-count selector buttons. The currently-active
     * count is shown disabled (acts as the indicator); clicking any other
     * count calls [EditorState.changeSwitchCases] which reshapes the
     * `case_i` input pins and drops stale edges.
     */
    val SwitchCases: @Composable (dev.nitka.nodewire.graph.Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val current = node.config.getInt("cases").coerceIn(2, 8)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        ) {
            Text(
                "Cases:",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            for (n in 2..8) {
                Button(
                    onClick = { editor?.changeSwitchCases(node.id, n) },
                    enabled = n != current,
                ) {
                    Text(n.toString())
                }
            }
        }
    }

    /**
     * Script: just a "📜 Edit" button that opens the full-screen
     * [ScriptEditorScreen]. The source lives server-side; edits apply on the
     * editor's close (apply-on-close). No on-card source preview.
     */
    val Script: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val nodeName = dev.nitka.nodewire.graph.NodeTypeRegistry.get(node.typeKey)?.displayName ?: "Script"
        Button(
            onClick = {
                val pos = editor?.pos ?: return@Button
                net.minecraft.client.Minecraft.getInstance().setScreen(
                    ScriptEditorScreen(pos, node.id, node.config.getString("src"), nodeName),
                )
            },
        ) {
            Text("📜 Edit")
        }
    }

    /**
     * Sequencer: row of 2/4/8/16 step-count selector buttons. Active count
     * is shown disabled. Clicking another count writes `steps` into the
     * node config.
     */
    val SequencerSteps: @Composable (dev.nitka.nodewire.graph.Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        val current = node.config.getInt("steps").coerceIn(2, 16)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        ) {
            Text(
                "Steps:",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            for (n in listOf(2, 4, 8, 16)) {
                Button(
                    onClick = {
                        editor?.updateNode(node.id) { n2 ->
                            n2.copy(config = n2.config.copy().apply { putInt("steps", n) })
                        }
                    },
                    enabled = n != current,
                ) {
                    Text(n.toString())
                }
            }
        }
    }
}
