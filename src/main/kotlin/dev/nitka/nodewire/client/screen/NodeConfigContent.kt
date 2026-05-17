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
import dev.nitka.nodewire.ui.theme.NwTheme

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
        var text by remember(node.id) { mutableStateOf(node.config.getInt("int").toString()) }
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
        var text by remember(node.id) { mutableStateOf(node.config.getFloat("float").toString()) }
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
        var text by remember(node.id) { mutableStateOf(node.config.getString("string")) }
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

    /** DELAY: number of ticks the input is held back by. */
    val DelayTicks: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember(node.id) { mutableStateOf(node.config.getInt("delay").toString()) }
        LabeledRow("Ticks") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { ch -> ch.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceIn(0, 200)
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putInt("delay", v)
                        })
                    }
                },
            )
        }
    }

    /** PROBABILITY: integer 0..100 percent. Used by Random Bool. */
    val Probability: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember(node.id) { mutableStateOf(node.config.getInt("probability").toString()) }
        LabeledRow("Chance %") {
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { ch -> ch.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceIn(0, 100)
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putInt("probability", v)
                        })
                    }
                },
            )
        }
    }

    /** RANDOM_INT: min/max integer range. */
    val IntRange: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var minText by remember(node.id) { mutableStateOf(node.config.getInt("min").toString()) }
        var maxText by remember(node.id) { mutableStateOf(node.config.getInt("max").toString()) }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Min") {
                TextInput(
                    modifier = Modifier.fillMaxWidth(),
                    value = minText,
                    onValueChange = { new ->
                        val filtered = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                        minText = filtered
                        editor?.updateNode(node.id) { n ->
                            n.copy(config = n.config.copy().apply {
                                putInt("min", filtered.toIntOrNull() ?: 0)
                            })
                        }
                    },
                )
            }
            LabeledRow("Max") {
                TextInput(
                    modifier = Modifier.fillMaxWidth(),
                    value = maxText,
                    onValueChange = { new ->
                        val filtered = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                        maxText = filtered
                        editor?.updateNode(node.id) { n ->
                            n.copy(config = n.config.copy().apply {
                                putInt("max", filtered.toIntOrNull() ?: 0)
                            })
                        }
                    },
                )
            }
        }
    }

    /** TIMER: integer period in ticks. */
    val TimerPeriod: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember(node.id) { mutableStateOf(node.config.getInt("period").toString()) }
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
        var face by remember(node.id) { mutableStateOf(node.config.getString("face").ifEmpty { "north" }) }
        LabeledRow("Face") {
            Select(
                options = FACES,
                selected = face,
                onSelect = { next ->
                    face = next
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
        var name by remember(node.id) { mutableStateOf(node.config.getString("name")) }
        var type by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name }))
        }
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
                        type = next
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
        var value by remember(node.id) { mutableStateOf(node.config.getBoolean("bool")) }
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Checkbox(
                checked = value,
                onCheckedChange = { v ->
                    value = v
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
        var type by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name }))
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Type") {
                Select(
                    options = CONSTANT_TYPES,
                    selected = type,
                    onSelect = { next ->
                        type = next
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
                PinType.VEC3 -> ConstantBodyVec3(node, editor)
                else -> Unit
            }
        }
    }

    private val CONSTANT_TYPES = listOf(
        PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.STRING, PinType.VEC3,
    )

    /**
     * LogicGate: single op selector. Switching op calls [EditorState.changeLogicGateOp]
     * which rebuilds the input pins (NOT → 1 pin; binary → 2 pins) and disconnects edges.
     */
    val LogicGate: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var op by remember(node.id) {
            mutableStateOf(node.config.getString("op").ifEmpty { "AND" })
        }
        LabeledRow("Op") {
            Select(
                options = LOGIC_OPS,
                selected = op,
                onSelect = { next ->
                    op = next
                    editor?.changeLogicGateOp(node.id, next)
                },
                label = { it },
            )
        }
    }

    private val LOGIC_OPS = listOf("AND", "OR", "NOT", "XOR", "NAND", "NOR", "XNOR")

    /**
     * Math: type selector (INT/FLOAT) + op selector. MOD is hidden when
     * type is FLOAT. Switching to FLOAT while op=MOD coerces op to ADD.
     */
    val Math: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var type by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.INT.name }))
        }
        var op by remember(node.id) {
            mutableStateOf(node.config.getString("op").ifEmpty { "ADD" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Type") {
                Select(
                    options = MATH_TYPES,
                    selected = type,
                    onSelect = { next ->
                        type = next
                        // MOD is INT-only; coerce op if switching to FLOAT
                        val coercedOp = if (next == PinType.FLOAT && op == "MOD") "ADD" else op
                        op = coercedOp
                        editor?.changeMathConfig(node.id, coercedOp, next)
                    },
                    label = { it.name.lowercase() },
                )
            }
            LabeledRow("Op") {
                Select(
                    options = opsForMathType(type),
                    selected = op,
                    onSelect = { next ->
                        op = next
                        editor?.changeMathConfig(node.id, next, type)
                    },
                    label = { it },
                )
            }
        }
    }

    private val MATH_TYPES = listOf(PinType.INT, PinType.FLOAT)

    /**
     * Compare: single type selector (INT/FLOAT). Input pin types switch;
     * the three boolean outputs (gt/eq/lt) are invariant.
     */
    val Compare: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var type by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.INT.name }))
        }
        LabeledRow("Type") {
            Select(
                options = MATH_TYPES,
                selected = type,
                onSelect = { next ->
                    type = next
                    editor?.changeCompareType(node.id, next)
                },
                label = { it.name.lowercase() },
            )
        }
    }

    private fun opsForMathType(t: PinType) = when (t) {
        PinType.INT -> listOf("ADD", "SUB", "MUL", "DIV", "MOD")
        else -> listOf("ADD", "SUB", "MUL", "DIV")
    }

    /**
     * Convert: source type + target type + (for REDSTONE pairs) mode selectors.
     * Valid pairs: INT↔FLOAT, INT↔BOOL, INT/FLOAT/BOOL→REDSTONE, REDSTONE→INT/FLOAT/BOOL.
     * Switching source resets target to the first valid option if the current target is
     * no longer valid for the new source. Mode row appears only for REDSTONE pairs.
     */
    val Convert: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var source by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("sourceType").ifEmpty { PinType.INT.name }))
        }
        var target by remember(node.id) {
            mutableStateOf(PinType.fromName(node.config.getString("targetType").ifEmpty { PinType.FLOAT.name }))
        }
        var mode by remember(node.id) {
            mutableStateOf(node.config.getString("mode"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("From") {
                Select(
                    options = CONVERT_SOURCES,
                    selected = source,
                    onSelect = { next ->
                        source = next
                        val validTargets = validTargetsFor(next)
                        val newTarget = if (target in validTargets) target else validTargets.first()
                        target = newTarget
                        editor?.changeConvertTypes(node.id, next, newTarget)
                        // Sync local mode after mutator default-applies it.
                        mode = node.config.getString("mode") // will be empty or default
                    },
                    label = { it.name.lowercase() },
                )
            }
            LabeledRow("To") {
                Select(
                    options = validTargetsFor(source),
                    selected = target,
                    onSelect = { next ->
                        target = next
                        editor?.changeConvertTypes(node.id, source, next)
                        mode = node.config.getString("mode")
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
                            mode = next
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
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    dim = next
                    editor?.changeVecMakeSplitDim(node.id, next)
                },
                label = { it.lowercase() },
            )
        }
    }

    /** VecSplit: same Select as VecMake; output pins reshape. */
    val VecSplit: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    dim = next
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
        var op by remember(node.id) {
            mutableStateOf(node.config.getString("op").ifEmpty { "ADD" })
        }
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Op") {
                Select(
                    options = VEC_OPS,
                    selected = op,
                    onSelect = { next ->
                        op = next
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
                            dim = next
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
}
