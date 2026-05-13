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
 *   * `bool_const` stores `value: byte` (0/1).
 *   * `int_const` stores `value: int`.
 *   * `float_const` stores `value: float`.
 *   * `vec3_const` stores `x,y,z: float`.
 *   * `timer` stores `period: int` (ticks).
 *
 * No-arg defaults match [PinValue.default] for the corresponding type.
 */
object StockNodeTypes {

    val BLOCK_INPUT = nodeType(
        id = "block_input",
        displayName = "Block Input",
        category = NodeCategory.IO,
        outputs = faceBoolPins(),
    )

    val BLOCK_OUTPUT = nodeType(
        id = "block_output",
        displayName = "Block Output",
        category = NodeCategory.IO,
        inputs = faceBoolPins(),
    )

    val AND = nodeType(
        id = "and",
        displayName = "AND",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    val OR = nodeType(
        id = "or",
        displayName = "OR",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    val NOT = nodeType(
        id = "not",
        displayName = "NOT",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("in", "In", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    val BOOL_CONST = nodeType(
        id = "bool_const",
        displayName = "Bool Constant",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putBoolean("value", false) } },
    )

    val INT_CONST = nodeType(
        id = "int_const",
        displayName = "Int Constant",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.INT)),
        defaultConfig = { CompoundTag().apply { putInt("value", 0) } },
    )

    val FLOAT_CONST = nodeType(
        id = "float_const",
        displayName = "Float Constant",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.FLOAT)),
        defaultConfig = { CompoundTag().apply { putFloat("value", 0f) } },
    )

    val VEC3_CONST = nodeType(
        id = "vec3_const",
        displayName = "Vec3 Constant",
        category = NodeCategory.CONSTANTS,
        outputs = listOf(Pin("out", "Value", PinType.VEC3)),
        defaultConfig = {
            CompoundTag().apply {
                putFloat("x", 0f); putFloat("y", 0f); putFloat("z", 0f)
            }
        },
    )

    val TIMER = nodeType(
        id = "timer",
        displayName = "Timer",
        category = NodeCategory.CONSTANTS,
        inputs = listOf(Pin("period", "Period", PinType.INT)),
        outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        defaultConfig = { CompoundTag().apply { putInt("period", 20) } },
    )

    val ADD_INT = nodeType(
        id = "add_int",
        displayName = "Add Int",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
        outputs = listOf(Pin("out", "Out", PinType.INT)),
    )

    val ADD_FLOAT = nodeType(
        id = "add_float",
        displayName = "Add Float",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.FLOAT), Pin("b", "B", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
    )

    val ADD_VEC3 = nodeType(
        id = "add_vec3",
        displayName = "Add Vec3",
        category = NodeCategory.MATH,
        inputs = listOf(Pin("a", "A", PinType.VEC3), Pin("b", "B", PinType.VEC3)),
        outputs = listOf(Pin("out", "Out", PinType.VEC3)),
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
    )

    /** Registers all 13 types into [NodeTypeRegistry]. Idempotent. */
    fun registerAll() {
        listOf(
            BLOCK_INPUT, BLOCK_OUTPUT,
            AND, OR, NOT,
            BOOL_CONST, INT_CONST, FLOAT_CONST, VEC3_CONST,
            TIMER,
            ADD_INT, ADD_FLOAT, ADD_VEC3, COMPARE_INT,
        ).forEach(NodeTypeRegistry::register)
    }

    private fun nodeType(
        id: String,
        displayName: String,
        category: NodeCategory,
        inputs: List<Pin> = emptyList(),
        outputs: List<Pin> = emptyList(),
        defaultConfig: () -> CompoundTag = { CompoundTag() },
    ) = NodeType(
        id = ResourceLocation(Nodewire.ID, id),
        displayName = displayName,
        category = category,
        inputs = inputs,
        outputs = outputs,
        defaultConfig = defaultConfig,
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
