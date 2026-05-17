package dev.nitka.nodewire.integration.tweakedcontroller

import net.minecraft.world.item.ItemStack
import net.minecraftforge.fml.ModList
import java.util.UUID

/**
 * Soft-dependency wrapper for the Create: Tweaked Controllers mod
 * (`create_tweaked_controllers` on Forge). All TC access goes through
 * reflection so Nodewire builds and runs with TC absent. Every public
 * method degrades gracefully — returns false / null / a zero state —
 * when TC isn't loaded or the cached class lookup fails.
 *
 * Class names confirmed from a jar spike against TC 1.20.1-1.2.6:
 *   * Controller item: TweakedLinkedControllerItem
 *   * Server state holders: TweakedLinkedControllerServerHandler.receivedInputs / .receivedAxes
 */
object TweakedController {

    const val MOD_ID: String = "create_tweaked_controllers"

    private val controllerItemClass: Class<*>? by lazy {
        try {
            Class.forName("com.getitemfromblock.create_tweaked_controllers.item.TweakedLinkedControllerItem")
        } catch (_: Throwable) {
            null
        }
    }

    /** True iff TC mod is loaded into the current runtime. */
    fun isLoaded(): Boolean = ModList.get()?.isLoaded(MOD_ID) ?: false

    /**
     * Returns the unique identifier of the controller represented by
     * [stack], or `null` if the stack isn't a TC controller item. The
     * id is derived from the item NBT — TC stores a UUID under one of
     * the conventional keys (controllerId / uuid / id). If your TC
     * version uses a different key, extend this method.
     */
    fun controllerItemId(stack: ItemStack): UUID? {
        if (!isLoaded() || stack.isEmpty) return null
        val klass = controllerItemClass ?: return null
        if (!klass.isInstance(stack.item)) return null
        val tag = stack.tag ?: return null
        if (tag.hasUUID("Id")) return tag.getUUID("Id")
        if (tag.hasUUID("controllerId")) return tag.getUUID("controllerId")
        if (tag.hasUUID("uuid")) return tag.getUUID("uuid")
        if (tag.hasUUID("id")) return tag.getUUID("id")
        return null
    }

    /**
     * Fetch the current input state for the controller [id]. Returns
     * `null` when TC isn't loaded, the controller is offline, or the
     * server-side state hasn't been replicated by TC.
     *
     * TODO(follow-up): wire reflection into
     *   TweakedLinkedControllerServerHandler.receivedInputs (WorldAttached<Map<UUID,Collection<TweakedManualFrequency>>>)
     *   and .receivedAxes (WorldAttached<Map<UUID,List<TweakedManualAxisFrequency>>>).
     * Until that's done, this returns null so the evaluator emits zero values.
     */
    @Suppress("UNUSED_PARAMETER")
    fun getControllerState(id: UUID): ControllerState? {
        if (!isLoaded()) return null
        return null
    }
}

/**
 * Plain data class holding one snapshot of a controller's gamepad
 * inputs. Stick axes in [−1f, 1f] (positive Y is "up"), triggers in
 * [0f, 1f], buttons boolean. Defaults are all-zero / all-false so the
 * evaluator never reads NaN.
 */
data class ControllerState(
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val buttonA: Boolean = false,
    val buttonB: Boolean = false,
    val buttonX: Boolean = false,
    val buttonY: Boolean = false,
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    val leftStickClick: Boolean = false,
    val rightStickClick: Boolean = false,
    val start: Boolean = false,
    val back: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
) {
    companion object {
        val ZERO = ControllerState()
    }
}
