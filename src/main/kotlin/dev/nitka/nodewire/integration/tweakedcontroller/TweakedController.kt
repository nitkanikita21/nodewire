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
     * Nodewire's own NBT key for a per-item-instance UUID. TC's controller
     * item has no inherent UUID — it identifies channels by configurable
     * frequencies (Create redstone-link style), not per-instance ids.
     * We mint our own UUID on first lookup and persist it on the stack so
     * the binding model ("this specific controller" ↔ "this logic block")
     * has something stable to point at. The UUID is opaque to TC and only
     * meaningful inside Nodewire.
     */
    private const val NW_BIND_ID_KEY = "nw:bindId"

    /**
     * Returns a stable identifier for the controller represented by [stack],
     * or `null` if the stack isn't a TC controller item. Allocates and
     * persists a UUID into the stack's NBT on first call — subsequent
     * calls return the same id. Caller must hold the stack on the server
     * side (item-NBT mutations sync to the client via vanilla item slot
     * sync). The reflection class check guards against accidentally tagging
     * non-controller stacks.
     */
    fun controllerItemId(stack: ItemStack): UUID? {
        if (!isLoaded() || stack.isEmpty) return null
        val klass = controllerItemClass ?: return null
        if (!klass.isInstance(stack.item)) return null
        val tag = stack.orCreateTag
        if (tag.hasUUID(NW_BIND_ID_KEY)) return tag.getUUID(NW_BIND_ID_KEY)
        val minted = UUID.randomUUID()
        tag.putUUID(NW_BIND_ID_KEY, minted)
        return minted
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
