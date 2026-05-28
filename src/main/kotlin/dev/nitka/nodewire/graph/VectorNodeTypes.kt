package dev.nitka.nodewire.graph

import dev.nitka.nodewire.Nodewire
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * Three vector-math node types — compose, decompose, and a universal
 * polymorphic op node. Registered into [NodeTypeRegistry] by
 * [StockNodeTypes.registerAll] (keeps a single registration entry point).
 *
 * Config conventions:
 *   * `dim` stores the working dimension ("VEC2" or "VEC3"). For ops
 *     whose dim is fixed (CROSS, ROTATE2D, TO_VEC2, TO_VEC3) the value
 *     is forced to the op's natural dim by [changeVecOp].
 *   * `op` (on vec_op only) stores the [VecOp] enum name.
 */
object VectorNodeTypes {

    val VEC_MAKE = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "vec_make"),
        displayName = "🧩 Vec Make",
        category = NodeCategory.VECTOR,
        inputs = listOf(
            Pin("x", "X", PinType.FLOAT),
            Pin("y", "Y", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.VEC2)),
        defaultConfig = { CompoundTag().apply { putString("dim", "VEC2") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.VecMake,
        evaluate = VectorEvaluators.VecMake,
        pinReshape = { config -> vecMakePinsFor(config.getString("dim").ifEmpty { "VEC2" }) },
    )

    val VEC_SPLIT = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "vec_split"),
        displayName = "✂ Vec Split",
        category = NodeCategory.VECTOR,
        inputs = listOf(Pin("in", "In", PinType.VEC2)),
        outputs = listOf(
            Pin("x", "X", PinType.FLOAT),
            Pin("y", "Y", PinType.FLOAT),
        ),
        defaultConfig = { CompoundTag().apply { putString("dim", "VEC2") } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.VecSplit,
        evaluate = VectorEvaluators.VecSplit,
        pinReshape = { config -> vecSplitPinsFor(config.getString("dim").ifEmpty { "VEC2" }) },
    )

    val VEC_OP = NodeType(
        id = ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "vec_op"),
        displayName = "➡ Vec Op",
        category = NodeCategory.VECTOR,
        // Default op = ADD on VEC2 → two Vec2 inputs, one Vec2 output.
        inputs = listOf(
            Pin("a", "A", PinType.VEC2),
            Pin("b", "B", PinType.VEC2),
        ),
        outputs = listOf(Pin("out", "Out", PinType.VEC2)),
        defaultConfig = {
            CompoundTag().apply {
                putString("op", "ADD")
                putString("dim", "VEC2")
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.VecOp,
        evaluate = VectorEvaluators.VecOp,
        pinReshape = { config ->
            val (_, ins, outs) = pinsForVecOp(
                config.getString("op").ifEmpty { "ADD" },
                config.getString("dim").ifEmpty { "VEC2" },
            )
            ins to outs
        },
    )

    fun all(): List<NodeType> = listOf(VEC_MAKE, VEC_SPLIT, VEC_OP)

    private fun vecMakePinsFor(dim: String): Pair<List<Pin>, List<Pin>> {
        val isVec2 = dim == "VEC2"
        val vecType = if (isVec2) PinType.VEC2 else PinType.VEC3
        val ins = mutableListOf(
            Pin("x", "X", PinType.FLOAT),
            Pin("y", "Y", PinType.FLOAT),
        )
        if (!isVec2) ins.add(Pin("z", "Z", PinType.FLOAT))
        return ins to listOf(Pin("out", "Out", vecType))
    }

    private fun vecSplitPinsFor(dim: String): Pair<List<Pin>, List<Pin>> {
        val isVec2 = dim == "VEC2"
        val vecType = if (isVec2) PinType.VEC2 else PinType.VEC3
        val outs = mutableListOf(
            Pin("x", "X", PinType.FLOAT),
            Pin("y", "Y", PinType.FLOAT),
        )
        if (!isVec2) outs.add(Pin("z", "Z", PinType.FLOAT))
        return listOf(Pin("in", "In", vecType)) to outs
    }

    /**
     * `(effectiveDim, inputs, outputs)` for a vec_op given its op + dim
     * config. Same shape as the EditorState reshape mutator returns —
     * lifted here so [Node.CODEC] can derive pins on decode without
     * pulling in the editor module.
     */
    fun pinsForVecOp(op: String, dim: String): Triple<String, List<Pin>, List<Pin>> {
        val effectiveDim = when (op) {
            "CROSS" -> "VEC3"
            "ROTATE2D" -> "VEC2"
            "TO_VEC3", "TO_VEC2" -> dim
            else -> if (dim == "VEC3") "VEC3" else "VEC2"
        }
        val V = if (effectiveDim == "VEC3") PinType.VEC3 else PinType.VEC2
        return when (op) {
            "ADD", "SUB", "MUL_COMPONENT", "MIN", "MAX" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
            "NEGATE", "NORMALIZE", "ABS" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V)),
                listOf(Pin("out", "Out", V)),
            )
            "SCALE" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("s", "S", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "CLAMP_MAG" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("max", "Max", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "LERP" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V), Pin("t", "T", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "PROJECT" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
            "REFLECT" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("n", "N", V)),
                listOf(Pin("out", "Out", V)),
            )
            "DOT", "DISTANCE", "ANGLE" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", PinType.FLOAT)),
            )
            "LENGTH", "LENGTH_SQ" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V)),
                listOf(Pin("out", "Out", PinType.FLOAT)),
            )
            "CROSS" -> Triple(
                "VEC3",
                listOf(Pin("a", "A", PinType.VEC3), Pin("b", "B", PinType.VEC3)),
                listOf(Pin("out", "Out", PinType.VEC3)),
            )
            "ROTATE2D" -> Triple(
                "VEC2",
                listOf(Pin("v", "V", PinType.VEC2), Pin("angle", "Angle", PinType.FLOAT)),
                listOf(Pin("out", "Out", PinType.VEC2)),
            )
            "TO_VEC3" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", PinType.VEC2), Pin("z", "Z", PinType.FLOAT)),
                listOf(Pin("out", "Out", PinType.VEC3)),
            )
            "TO_VEC2" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", PinType.VEC3)),
                listOf(Pin("out", "Out", PinType.VEC2)),
            )
            else -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
        }
    }
}
