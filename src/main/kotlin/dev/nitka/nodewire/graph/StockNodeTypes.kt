package dev.nitka.nodewire.graph

import dev.nitka.nodewire.Nodewire
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

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
        displayName = "Side Input",
        category = NodeCategory.IO,
        outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
        defaultConfig = { CompoundTag().apply { putString("face", "north") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SideFace,
        evaluate = StockEvaluators.SideInput,
    )

    val SIDE_OUTPUT = nodeType(
        id = "side_output",
        displayName = "Side Output",
        category = NodeCategory.IO,
        inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
        defaultConfig = { CompoundTag().apply { putString("face", "north") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SideFace,
        evaluate = StockEvaluators.SideOutput,
    )

    val CHANNEL_INPUT = nodeType(
        id = "channel_input",
        displayName = "Channel Input",
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
        displayName = "Channel Output",
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

    val CONVERT_TO_REDSTONE = nodeType(
        id = "convert_to_redstone",
        displayName = "To Redstone",
        category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "In", PinType.INT)),
        outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
        defaultConfig = {
            CompoundTag().apply {
                putString("sourceType", PinType.INT.name)
                putString("mode", "clamp")
                putInt("threshold", 1)
                putInt("min", 0)
                putInt("max", 15)
                putInt("level", 15)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.ConvertToRedstone,
        evaluate = StockEvaluators.ConvertToRedstone,
    )

    val FROM_REDSTONE = nodeType(
        id = "from_redstone",
        displayName = "From Redstone",
        category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
        outputs = listOf(Pin("out", "Out", PinType.INT)),
        defaultConfig = {
            CompoundTag().apply {
                putString("targetType", PinType.INT.name)
                putString("mode", "raw")
                putInt("threshold", 1)
                putInt("min", 0)
                putInt("max", 15)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.FromRedstone,
        evaluate = StockEvaluators.FromRedstone,
    )

    val LOGIC_GATE = nodeType(
        id = "logic_gate",
        displayName = "Logic Gate",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putString("op", "AND") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.LogicGate,
        evaluate = StockEvaluators.LogicGate,
    )

    val CONSTANT = nodeType(
        id = "constant",
        displayName = "Constant",
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
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Constant,
        evaluate = StockEvaluators.Constant,
    )

    val TIMER = nodeType(
        id = "timer",
        displayName = "Timer",
        category = NodeCategory.CONSTANTS,
        // No `period` input pin — value comes from config; the node ticks
        // a counter on the server and toggles `out` every config.period.
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putInt("period", 20) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.TimerPeriod,
        evaluate = StockEvaluators.Timer,
        tickEvaluator = StockEvaluators.TimerTick,
    )

    val ADD_INT = nodeType(
        id = "add_int",
        displayName = "Add Int",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
        outputs = listOf(Pin("out", "Out", PinType.INT)),
        evaluate = StockEvaluators.AddInt,
    )

    val ADD_FLOAT = nodeType(
        id = "add_float",
        displayName = "Add Float",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.FLOAT), Pin("b", "B", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.AddFloat,
    )

    val ADD_VEC3 = nodeType(
        id = "add_vec3",
        displayName = "Add Vec3",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.VEC3), Pin("b", "B", PinType.VEC3)),
        outputs = listOf(Pin("out", "Out", PinType.VEC3)),
        evaluate = StockEvaluators.AddVec3,
    )

    val COMPARE_INT = nodeType(
        id = "compare_int",
        displayName = "Compare Int",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
        outputs = listOf(
            Pin("gt", "A > B", PinType.BOOL),
            Pin("eq", "A = B", PinType.BOOL),
            Pin("lt", "A < B", PinType.BOOL),
        ),
        evaluate = StockEvaluators.CompareInt,
    )

    val COMPARE_FLOAT = nodeType(
        id = "compare_float",
        displayName = "Compare Float",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.FLOAT), Pin("b", "B", PinType.FLOAT)),
        outputs = listOf(
            Pin("gt", "A > B", PinType.BOOL),
            Pin("eq", "A = B", PinType.BOOL),
            Pin("lt", "A < B", PinType.BOOL),
        ),
        evaluate = StockEvaluators.CompareFloat,
    )

    val SUB_INT = intBinary("sub_int", "Subtract Int", StockEvaluators.SubInt)
    val SUB_FLOAT = floatBinary("sub_float", "Subtract Float", StockEvaluators.SubFloat)
    val MUL_INT = intBinary("mul_int", "Multiply Int", StockEvaluators.MulInt)
    val MUL_FLOAT = floatBinary("mul_float", "Multiply Float", StockEvaluators.MulFloat)
    val DIV_INT = intBinary("div_int", "Divide Int", StockEvaluators.DivInt)
    val DIV_FLOAT = floatBinary("div_float", "Divide Float", StockEvaluators.DivFloat)
    val MOD_INT = intBinary("mod_int", "Modulo Int", StockEvaluators.ModInt)
    val NEG_FLOAT = floatUnary("neg_float", "Negate Float", StockEvaluators.NegFloat)
    val ABS_FLOAT = floatUnary("abs_float", "Abs Float", StockEvaluators.AbsFloat)
    val MIN_FLOAT = floatBinary("min_float", "Min Float", StockEvaluators.MinFloat)
    val MAX_FLOAT = floatBinary("max_float", "Max Float", StockEvaluators.MaxFloat)

    val CLAMP_FLOAT = nodeType(
        id = "clamp_float",
        displayName = "Clamp Float",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("in", "Value", PinType.FLOAT),
            Pin("min", "Min", PinType.FLOAT),
            Pin("max", "Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.ClampFloat,
    )

    // --- Conversion ----------------------------------------------------

    val INT_TO_FLOAT = nodeType(
        id = "int_to_float", displayName = "Int → Float", category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "Int", PinType.INT)),
        outputs = listOf(Pin("out", "Float", PinType.FLOAT)),
        evaluate = StockEvaluators.IntToFloat,
    )
    val FLOAT_TO_INT = nodeType(
        id = "float_to_int", displayName = "Float → Int", category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "Float", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Int", PinType.INT)),
        evaluate = StockEvaluators.FloatToInt,
    )
    val BOOL_TO_INT = nodeType(
        id = "bool_to_int", displayName = "Bool → Int", category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "Bool", PinType.BOOL)),
        outputs = listOf(Pin("out", "Int", PinType.INT)),
        evaluate = StockEvaluators.BoolToInt,
    )
    val INT_TO_BOOL = nodeType(
        id = "int_to_bool", displayName = "Int → Bool", category = NodeCategory.CONVERSION,
        inputs = listOf(Pin("in", "Int", PinType.INT)),
        outputs = listOf(Pin("out", "Bool", PinType.BOOL)),
        evaluate = StockEvaluators.IntToBool,
    )

    // --- Flow ----------------------------------------------------------

    val SELECT_BOOL = nodeType(
        id = "select_bool", displayName = "Select Bool", category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("pred", "If", PinType.BOOL),
            Pin("a", "Then", PinType.BOOL),
            Pin("b", "Else", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        evaluate = StockEvaluators.SelectBool,
    )

    val EDGE_RISING = nodeType(
        id = "edge_rising", displayName = "Rising Edge", category = NodeCategory.FLOW,
        inputs = listOf(Pin("in", "In", PinType.BOOL)),
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        tickEvaluator = StockEvaluators.EdgeRisingTick,
    )

    val TOGGLE = nodeType(
        id = "toggle", displayName = "Toggle", category = NodeCategory.FLOW,
        inputs = listOf(Pin("in", "Pulse", PinType.BOOL)),
        outputs = listOf(Pin("out", "State", PinType.BOOL)),
        tickEvaluator = StockEvaluators.ToggleTick,
    )

    val COUNTER = nodeType(
        id = "counter", displayName = "Counter", category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("in", "Pulse", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Count", PinType.INT)),
        tickEvaluator = StockEvaluators.CounterTick,
    )

    val DELAY = nodeType(
        id = "delay", displayName = "Delay", category = NodeCategory.FLOW,
        inputs = listOf(Pin("in", "In", PinType.BOOL)),
        outputs = listOf(Pin("out", "Delayed", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putInt("delay", 5) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.DelayTicks,
        tickEvaluator = StockEvaluators.DelayTick,
    )

    // --- Test / Generators --------------------------------------------

    val RANDOM_BOOL = nodeType(
        id = "random_bool",
        displayName = "Random Bool",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putInt("probability", 50) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Probability,
        evaluate = StockEvaluators.RandomBool,
    )

    val RANDOM_INT = nodeType(
        id = "random_int",
        displayName = "Random Int",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.INT)),
        defaultConfig = { CompoundTag().apply { putInt("min", 0); putInt("max", 15) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.IntRange,
        evaluate = StockEvaluators.RandomInt,
    )

    val PULSE = nodeType(
        id = "pulse",
        displayName = "Pulse",
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
            // Logic
            LOGIC_GATE,
            // Constants
            CONSTANT, TIMER,
            // Math
            ADD_INT, ADD_FLOAT, ADD_VEC3,
            SUB_INT, SUB_FLOAT,
            MUL_INT, MUL_FLOAT,
            DIV_INT, DIV_FLOAT, MOD_INT,
            NEG_FLOAT, ABS_FLOAT, MIN_FLOAT, MAX_FLOAT, CLAMP_FLOAT,
            COMPARE_INT, COMPARE_FLOAT,
            // Conversion
            INT_TO_FLOAT, FLOAT_TO_INT, BOOL_TO_INT, INT_TO_BOOL, CONVERT_TO_REDSTONE, FROM_REDSTONE,
            // Flow
            SELECT_BOOL, EDGE_RISING, TOGGLE, COUNTER, DELAY,
            // Test / Generators
            RANDOM_BOOL, RANDOM_INT, PULSE,
        ).forEach(NodeTypeRegistry::register)
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
        id = ResourceLocation(Nodewire.ID, id),
        displayName = displayName,
        category = category,
        inputs = inputs,
        outputs = outputs,
        defaultConfig = defaultConfig,
        configContent = configContent,
        evaluate = evaluate,
        tickEvaluator = tickEvaluator,
    )

    // --- Helpers for common pin shapes ---------------------------------

    private fun intBinary(id: String, displayName: String, eval: NodeEvaluator) = nodeType(
        id = id, displayName = displayName, category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
        outputs = listOf(Pin("out", "Out", PinType.INT)),
        evaluate = eval,
    )

    private fun floatBinary(id: String, displayName: String, eval: NodeEvaluator) = nodeType(
        id = id, displayName = displayName, category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.FLOAT), Pin("b", "B", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = eval,
    )

    private fun floatUnary(id: String, displayName: String, eval: NodeEvaluator) = nodeType(
        id = id, displayName = displayName, category = NodeCategory.MATH,
        inputs = listOf(Pin("in", "In", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = eval,
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
