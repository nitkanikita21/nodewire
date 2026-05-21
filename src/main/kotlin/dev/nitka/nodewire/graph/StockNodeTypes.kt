package dev.nitka.nodewire.graph

import dev.nitka.nodewire.Nodewire
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceLocation

/**
 * Encode a [PinValue] into the `pinDefaults` compound under [pinId].
 * Helper for [NodeType.defaultConfig] blocks that need to seed inline
 * defaults for input pins migrated away from raw config keys.
 */
private fun CompoundTag.seedPinDefault(pinId: String, value: PinValue) {
    val pd = (get("pinDefaults") as? CompoundTag) ?: CompoundTag().also { put("pinDefaults", it) }
    PinValue.CODEC.encodeStart(NbtOps.INSTANCE, value).result().ifPresent { pd.put(pinId, it) }
}

/**
 * The 13 baseline [NodeType]s from the MVP spec. Calling [registerAll]
 * once at mod init populates [NodeTypeRegistry] with all of them.
 *
 * Pin id conventions:
 *   * Logic / Math binary: `a`, `b`; unary: `in`; output: `out`.
 *   * IO block ports: one pin per face id (`down`, `up`, `north`, `south`,
 *     `west`, `east`). The face order matches `Direction.values()` so the
 *     editor can render them in a stable order.
 *   * Compare-int has three outputs (`gt`, `eq`, `lt`).
 *
 * Config conventions:
 *   * `constant` stores `type: String` + per-type slots: `bool`, `int`,
 *     `float`, `string`, and `x/y/z` (VEC3).
 *   * `timer` stores `period: int` (ticks).
 *
 * No-arg defaults match [PinValue.default] for the corresponding type.
 */
object StockNodeTypes {

    // --- I/O ----------------------------------------------------------
    //
    // The legacy multi-face BLOCK_INPUT / BLOCK_OUTPUT are gone — replaced
    // by:
    //   * SideInput  — reads vanilla redstone from ONE configured face.
    //   * SideOutput — writes vanilla redstone to ONE configured face.
    //   * ChannelInput / ChannelOutput — typed named channels for linking
    //     between two logic blocks via the (yet-to-be-built) link tool.

    val SIDE_INPUT = nodeType(
        id = "side_input",
        displayName = "🔌 Side Input",
        category = NodeCategory.IO,
        outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
        defaultConfig = { CompoundTag().apply { putString("face", "north") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SideFace,
        evaluate = StockEvaluators.SideInput,
    )

    val SIDE_OUTPUT = nodeType(
        id = "side_output",
        displayName = "⚡ Side Output",
        category = NodeCategory.IO,
        inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
        defaultConfig = { CompoundTag().apply { putString("face", "north") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SideFace,
        evaluate = StockEvaluators.SideOutput,
    )

    val CHANNEL_INPUT = nodeType(
        id = "channel_input",
        displayName = "📥 Channel Input",
        category = NodeCategory.IO,
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply {
                putString("name", "")
                putString("type", PinType.BOOL.name)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.ChannelEndpoint,
        evaluate = StockEvaluators.ChannelInput,
    )

    val CHANNEL_OUTPUT = nodeType(
        id = "channel_output",
        displayName = "📤 Channel Output",
        category = NodeCategory.IO,
        inputs = listOf(Pin("in", "Value", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply {
                putString("name", "")
                putString("type", PinType.BOOL.name)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.ChannelEndpoint,
        evaluate = StockEvaluators.ChannelOutput,
    )

    val REDSTONE_LINK_INPUT = nodeType(
        id = "redstone_link_input",
        displayName = "📡 Redstone Link Input",
        category = NodeCategory.IO,
        outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
        defaultConfig = {
            // freq1/freq2 are ItemStack NBT compounds; empty compound → ItemStack.EMPTY on read.
            CompoundTag().apply {
                put("freq1", CompoundTag())
                put("freq2", CompoundTag())
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.RedstoneLinkFrequency,
        evaluate = StockEvaluators.RedstoneLinkInput,
    )

    val REDSTONE_LINK_OUTPUT = nodeType(
        id = "redstone_link_output",
        displayName = "📡 Redstone Link Output",
        category = NodeCategory.IO,
        inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
        defaultConfig = {
            // freq1/freq2 are ItemStack NBT compounds; empty compound → ItemStack.EMPTY on read.
            CompoundTag().apply {
                put("freq1", CompoundTag())
                put("freq2", CompoundTag())
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.RedstoneLinkFrequency,
        evaluate = StockEvaluators.RedstoneLinkOutput,
    )

    val LOGIC_GATE = nodeType(
        id = "logic_gate",
        displayName = "🧮 Logic Gate",
        category = NodeCategory.LOGIC,
        inputs = listOf(
            Pin("op", "Op", PinType.STRING, editor = PinEditor.Enum(
                listOf("AND", "OR", "NOT", "XOR", "NAND", "NOR", "XNOR")
            )),
            Pin("a", "A", PinType.BOOL),
            Pin("b", "B", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply { seedPinDefault("op", PinValue.Str("AND")) }
        },
        evaluate = StockEvaluators.LogicGate,
    )

    val CONSTANT = nodeType(
        id = "constant",
        displayName = "🔢 Constant",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply {
                putString("type", PinType.BOOL.name)
                putBoolean("bool", false)
                putInt("int", 0)
                putFloat("float", 0f)
                putString("string", "")
                putFloat("x", 0f); putFloat("y", 0f); putFloat("z", 0f)
                putFloat("x2", 0f); putFloat("y2", 0f)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Constant,
        evaluate = StockEvaluators.Constant,
    )

    val TIMER = nodeType(
        id = "timer",
        displayName = "⏱ Timer",
        category = NodeCategory.CONSTANTS,
        inputs = listOf(Pin("period", "Period", PinType.INT)),
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply { seedPinDefault("period", PinValue.Int(20)) }
        },
        evaluate = StockEvaluators.Timer,
        tickEvaluator = StockEvaluators.TimerTick,
    )

    val MATH = nodeType(
        id = "math",
        displayName = "➗ Math",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("op", "Op", PinType.STRING, editor = PinEditor.Enum(
                listOf("ADD", "SUB", "MUL", "DIV", "MOD")
            )),
            Pin("a", "A", PinType.FLOAT),
            Pin("b", "B", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        defaultConfig = {
            CompoundTag().apply { seedPinDefault("op", PinValue.Str("ADD")) }
        },
        evaluate = StockEvaluators.Math,
    )

    val COMPARE = nodeType(
        id = "compare",
        displayName = "⚖ Compare",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("a", "A", PinType.FLOAT),
            Pin("b", "B", PinType.FLOAT),
        ),
        outputs = listOf(
            Pin("gt", "A > B", PinType.BOOL),
            Pin("eq", "A = B", PinType.BOOL),
            Pin("lt", "A < B", PinType.BOOL),
        ),
        evaluate = StockEvaluators.Compare,
    )

    val CLAMP = nodeType(
        id = "clamp",
        displayName = "📏 Clamp",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("value", "Value", PinType.FLOAT),
            Pin("min", "Min", PinType.FLOAT),
            Pin("max", "Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Clamp,
    )

    val MAP = nodeType(
        id = "map",
        displayName = "↗ Map",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("value", "Value", PinType.FLOAT),
            Pin("from_min", "From Min", PinType.FLOAT),
            Pin("from_max", "From Max", PinType.FLOAT),
            Pin("to_min", "To Min", PinType.FLOAT),
            Pin("to_max", "To Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Map,
    )

    val LERP = nodeType(
        id = "lerp",
        displayName = "🌊 Lerp",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("a", "A", PinType.FLOAT),
            Pin("b", "B", PinType.FLOAT),
            Pin("t", "T", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Lerp,
    )

    // --- Conversion ----------------------------------------------------

    val CONVERT = nodeType(
        id = "convert",
        displayName = "🔄 Convert",
        category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "In", PinType.INT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        defaultConfig = {
            CompoundTag().apply {
                putString("sourceType", PinType.INT.name)
                putString("targetType", PinType.FLOAT.name)
                putString("mode", "")
                putInt("threshold", 1)
                putFloat("thresholdF", 1f)
                putInt("min", 0)
                putInt("max", 15)
                putFloat("minF", 0f)
                putFloat("maxF", 1f)
                putInt("level", 15)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Convert,
        evaluate = StockEvaluators.Convert,
    )

    // --- Flow ----------------------------------------------------------

    val SELECT_BOOL = nodeType(
        id = "select_bool", displayName = "🔀 Select Bool", category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("pred", "If", PinType.BOOL),
            Pin("a", "Then", PinType.BOOL),
            Pin("b", "Else", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        evaluate = StockEvaluators.SelectBool,
    )

    val IF_THEN_ELSE = nodeType(
        id = "if_then_else",
        displayName = "❓ If Then Else",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("cond", "Cond", PinType.BOOL),
            Pin("then", "Then", PinType.ANY),
            Pin("else_", "Else", PinType.ANY),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        evaluate = StockEvaluators.IfThenElse,
    )

    val SWITCH = nodeType(
        id = "switch",
        displayName = "🔀 Switch",
        category = NodeCategory.FLOW,
        inputs = StockEvaluators.switchInputs(4),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        defaultConfig = { CompoundTag().apply { putInt("cases", 4) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SwitchCases,
        evaluate = StockEvaluators.Switch,
    )

    val EDGE_RISING = nodeType(
        id = "edge_rising", displayName = "📈 Rising Edge", category = NodeCategory.FLOW,
        inputs = listOf(Pin("in", "In", PinType.BOOL)),
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        tickEvaluator = StockEvaluators.EdgeRisingTick,
    )

    val TOGGLE = nodeType(
        id = "toggle", displayName = "🔁 Toggle", category = NodeCategory.FLOW,
        inputs = listOf(Pin("in", "Pulse", PinType.BOOL)),
        outputs = listOf(Pin("out", "State", PinType.BOOL)),
        tickEvaluator = StockEvaluators.ToggleTick,
    )

    val COUNTER = nodeType(
        id = "counter", displayName = "🧮 Counter", category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("in", "Pulse", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Count", PinType.INT)),
        tickEvaluator = StockEvaluators.CounterTick,
    )

    val DELAY = nodeType(
        id = "delay", displayName = "⏳ Delay", category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("in", "In", PinType.BOOL),
            Pin("delay", "Delay", PinType.INT),
        ),
        outputs = listOf(Pin("out", "Delayed", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply { seedPinDefault("delay", PinValue.Int(5)) }
        },
        tickEvaluator = StockEvaluators.DelayTick,
    )

    val SAMPLE_HOLD = nodeType(
        id = "sample_hold",
        displayName = "📷 Sample & Hold",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("value", "Value", PinType.ANY),
            Pin("trigger", "Trigger", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        tickEvaluator = StockEvaluators.SampleHold,
    )

    val LATCH_SR = nodeType(
        id = "latch_sr",
        displayName = "🔒 Latch SR",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("set", "Set", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        tickEvaluator = StockEvaluators.LatchSr,
    )

    val LATCH_D = nodeType(
        id = "latch_d",
        displayName = "📌 Latch D",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("data", "Data", PinType.ANY),
            Pin("clock", "Clock", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        tickEvaluator = StockEvaluators.LatchD,
    )

    val SEQUENCER = nodeType(
        id = "sequencer",
        displayName = "🎼 Sequencer",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("advance", "Advance", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("step", "Step", PinType.INT)),
        defaultConfig = { CompoundTag().apply { putInt("steps", 4) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SequencerSteps,
        tickEvaluator = StockEvaluators.Sequencer,
    )

    val SMOOTH = nodeType(
        id = "smooth",
        displayName = "🌫 Smooth",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("target", "Target", PinType.FLOAT),
            Pin("factor", "Factor", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        tickEvaluator = StockEvaluators.Smooth,
    )

    val PID = nodeType(
        id = "pid",
        displayName = "🎯 PID",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("setpoint", "Setpoint", PinType.FLOAT),
            Pin("measurement", "Measurement", PinType.FLOAT),
            Pin("kp", "Kp", PinType.FLOAT),
            Pin("ki", "Ki", PinType.FLOAT),
            Pin("kd", "Kd", PinType.FLOAT),
            Pin("i_min", "I Min", PinType.FLOAT),
            Pin("i_max", "I Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        defaultConfig = {
            CompoundTag().apply {
                seedPinDefault("i_min", PinValue.Float(-1000f))
                seedPinDefault("i_max", PinValue.Float(1000f))
            }
        },
        tickEvaluator = StockEvaluators.Pid,
    )

    // --- Test / Generators --------------------------------------------

    val RANDOM_BOOL = nodeType(
        id = "random_bool",
        displayName = "🎲 Random Bool",
        category = NodeCategory.CONSTANTS,
        inputs = listOf(Pin("p", "P", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        defaultConfig = {
            CompoundTag().apply { seedPinDefault("p", PinValue.Float(0.5f)) }
        },
        evaluate = StockEvaluators.RandomBool,
    )

    val RANDOM_INT = nodeType(
        id = "random_int",
        displayName = "🎲 Random Int",
        category = NodeCategory.CONSTANTS,
        inputs = listOf(
            Pin("min", "Min", PinType.INT),
            Pin("max", "Max", PinType.INT),
        ),
        outputs = listOf(Pin("out", "Value", PinType.INT)),
        defaultConfig = {
            CompoundTag().apply {
                seedPinDefault("min", PinValue.Int(0))
                seedPinDefault("max", PinValue.Int(15))
            }
        },
        evaluate = StockEvaluators.RandomInt,
    )

    val PULSE = nodeType(
        id = "pulse",
        displayName = "💓 Pulse",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putInt("period", 20) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.TimerPeriod,
        evaluate = StockEvaluators.Pulse,
        tickEvaluator = StockEvaluators.PulseTick,
    )

    /** Registers every stock type into [NodeTypeRegistry]. Idempotent. */
    fun registerAll() {
        listOf(
            // IO
            SIDE_INPUT, SIDE_OUTPUT, CHANNEL_INPUT, CHANNEL_OUTPUT,
            REDSTONE_LINK_INPUT, REDSTONE_LINK_OUTPUT,
            // Logic
            LOGIC_GATE,
            // Constants
            CONSTANT, TIMER,
            // Math
            MATH, COMPARE, CLAMP, MAP, LERP, SMOOTH, PID,
            // Conversion
            CONVERT,
            // Flow
            SELECT_BOOL, IF_THEN_ELSE, SWITCH, EDGE_RISING, TOGGLE, COUNTER, DELAY,
            SAMPLE_HOLD, LATCH_SR, LATCH_D, SEQUENCER,
            // Test / Generators
            RANDOM_BOOL, RANDOM_INT, PULSE,
        ).forEach(NodeTypeRegistry::register)
        VectorNodeTypes.all().forEach(NodeTypeRegistry::register)
        NodeTypeRegistry.register(
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode.CONTROLLER_INPUT,
        )
        NodeTypeRegistry.register(
            dev.nitka.nodewire.integration.aeronautics.AeroInputNode.AERONAUTICS_INPUT,
        )
    }

    private fun nodeType(
        id: String,
        displayName: String,
        category: NodeCategory,
        inputs: List<Pin> = emptyList(),
        outputs: List<Pin> = emptyList(),
        defaultConfig: () -> CompoundTag = { CompoundTag() },
        configContent: (@androidx.compose.runtime.Composable (Node) -> Unit)? = null,
        evaluate: NodeEvaluator? = null,
        tickEvaluator: TickEvaluator? = null,
    ) = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, id),
        displayName = displayName,
        category = category,
        inputs = inputs,
        outputs = outputs,
        defaultConfig = defaultConfig,
        configContent = configContent,
        evaluate = evaluate,
        tickEvaluator = tickEvaluator,
    )

    private fun faceBoolPins(): List<Pin> = listOf(
        Pin("down", "Down", PinType.BOOL),
        Pin("up", "Up", PinType.BOOL),
        Pin("north", "North", PinType.BOOL),
        Pin("south", "South", PinType.BOOL),
        Pin("west", "West", PinType.BOOL),
        Pin("east", "East", PinType.BOOL),
    )
}
