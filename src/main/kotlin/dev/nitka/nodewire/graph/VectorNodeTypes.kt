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
        id = ResourceLocation(Nodewire.ID, "vec_make"),
        displayName = "Vec Make",
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

    fun all(): List<NodeType> = listOf(VEC_MAKE)
}
