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
    )

    fun all(): List<NodeType> = listOf(VEC_MAKE, VEC_SPLIT, VEC_OP)
}
