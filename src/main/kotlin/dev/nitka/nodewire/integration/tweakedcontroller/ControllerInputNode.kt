package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeEvaluator
import dev.nitka.nodewire.graph.NodeType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * `controller_input` NodeType — reads gamepad state from the Tweaked
 * Controller item bound to this Logic Block.
 *
 * The evaluator is pure (NodeEvaluator, not TickEvaluator) but it reads
 * side-channel state via [currentControllerId]. The host ([LogicBlockEntity])
 * must set that ThreadLocal before calling the evaluator and restore it
 * afterward (try/finally). This keeps the evaluator signature clean while
 * still allowing world-context access at eval time.
 *
 * When TC is absent or the controller is offline, the evaluator emits zero
 * values for every output pin.
 */
object ControllerInputNode {

    /**
     * Per-tick evaluator context: level + Logic Block origin + bound
     * controllerId. Set by [dev.nitka.nodewire.block.LogicBlockEntity.serverTick]
     * before [dev.nitka.nodewire.graph.StatefulGraphEvaluator.tick],
     * restored in `finally`. Null outside an active tick.
     *
     * The evaluator needs more than just the UUID because TC state is read
     * from a *lectern* near the Logic Block, not from a global UUID map —
     * see [TweakedController.getControllerState].
     */
    data class EvalContext(val level: Level, val origin: BlockPos, val controllerId: UUID?)

    val currentContext: ThreadLocal<EvalContext?> = ThreadLocal.withInitial { null }

    val Evaluator: NodeEvaluator = { config, _ ->
        val channel = ControllerChannel.fromName(config.getString("channel"))
        val mode = runCatching {
            ControllerOutputMode.valueOf(config.getString("outputMode"))
        }.getOrElse { ControllerOutputMode.VEC2_RAW }
        val deadzone = config.getFloat("deadzone")
        val invert = config.getBoolean("invert")

        val ctx = currentContext.get()
        val state = ctx?.controllerId?.let {
            TweakedController.getControllerState(ctx.level, ctx.origin, it)
        }

        if (state == null) {
            // TC absent, controller offline, or no id bound — emit zero values
            // for every pin that pinsForControllerInput would produce.
            pinsForControllerInput(channel, mode).associate { pin ->
                pin.id to when (pin.type) {
                    dev.nitka.nodewire.graph.PinType.VEC2 -> PinValue.Vec2(0f, 0f)
                    dev.nitka.nodewire.graph.PinType.FLOAT -> PinValue.Float(0f)
                    dev.nitka.nodewire.graph.PinType.REDSTONE -> PinValue.Redstone(0)
                    dev.nitka.nodewire.graph.PinType.BOOL -> PinValue.Bool(false)
                    else -> PinValue.Bool(false)
                }
            }
        } else {
            applyOutputMode(state, channel, mode, deadzone, invert)
        }
    }

    val CONTROLLER_INPUT: NodeType = NodeType(
        id = ResourceLocation(Nodewire.ID, "controller_input"),
        displayName = "Controller Input",
        category = NodeCategory.IO,
        inputs = emptyList(),
        outputs = pinsForControllerInput(ControllerChannel.LEFT_STICK, ControllerOutputMode.VEC2_RAW),
        defaultConfig = {
            CompoundTag().apply {
                putString("channel", ControllerChannel.LEFT_STICK.name)
                putString("outputMode", ControllerOutputMode.VEC2_RAW.name)
                putFloat("deadzone", 0.1f)
                putBoolean("invert", false)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.ControllerInput,
        evaluate = Evaluator,
    )
}
