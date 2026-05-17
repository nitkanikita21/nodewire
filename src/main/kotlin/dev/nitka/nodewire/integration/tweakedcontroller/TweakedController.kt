package dev.nitka.nodewire.integration.tweakedcontroller

import net.minecraft.world.item.ItemStack
import net.minecraftforge.fml.ModList

/**
 * Soft-dependency wrapper for the Create: Tweaked Controllers mod
 * (`create_tweaked_controllers` on Forge). Mostly a check-for-presence
 * facade — actual state flows via Mixin into TC's packet handlers
 * (see `dev.nitka.nodewire.mixin.tc`), pushed to [ControllerStatePipeline].
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
     * True iff [stack] is a Tweaked Controller item. Uses reflection
     * (TC may be absent at runtime).
     */
    fun isControllerItem(stack: ItemStack): Boolean {
        if (!isLoaded() || stack.isEmpty) return false
        val klass = controllerItemClass ?: return false
        return klass.isInstance(stack.item)
    }
}

/**
 * Plain data class holding one snapshot of a controller's gamepad
 * inputs. Stick axes in [−1f, 1f] (positive Y is "up"), triggers in
 * [0f, 1f], buttons boolean. Defaults are all-zero / all-false so the
 * evaluator never reads NaN.
 */
/**
 * GLFW gamepad button indices used by Tweaked Controller's wire format
 * (verified by inspecting TC's `TweakedLecternControllerBlockEntity.GetButton`).
 */
private const val BUTTON_A = 0
private const val BUTTON_B = 1
private const val BUTTON_X = 2
private const val BUTTON_Y = 3
private const val BUTTON_LB = 4
private const val BUTTON_RB = 5
private const val BUTTON_BACK = 6
private const val BUTTON_START = 7
// 8 = guide (unused by Nodewire)
private const val BUTTON_L_STICK_CLICK = 9
private const val BUTTON_R_STICK_CLICK = 10
private const val BUTTON_DPAD_UP = 11
private const val BUTTON_DPAD_RIGHT = 12
private const val BUTTON_DPAD_DOWN = 13
private const val BUTTON_DPAD_LEFT = 14

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

    /**
     * Apply a button-bitmask update from TC's wire format. Each of the
     * 15 used bits is one button; layout matches GLFW gamepad indices.
     * Axes are preserved (only button fields are rewritten).
     */
    fun withButtons(buttonStates: Short): ControllerState {
        val bits = buttonStates.toInt() and 0xFFFF
        fun bit(i: Int) = (bits shr i) and 1 != 0
        return copy(
            buttonA = bit(BUTTON_A),
            buttonB = bit(BUTTON_B),
            buttonX = bit(BUTTON_X),
            buttonY = bit(BUTTON_Y),
            leftBumper = bit(BUTTON_LB),
            rightBumper = bit(BUTTON_RB),
            back = bit(BUTTON_BACK),
            start = bit(BUTTON_START),
            leftStickClick = bit(BUTTON_L_STICK_CLICK),
            rightStickClick = bit(BUTTON_R_STICK_CLICK),
            dpadUp = bit(BUTTON_DPAD_UP),
            dpadRight = bit(BUTTON_DPAD_RIGHT),
            dpadDown = bit(BUTTON_DPAD_DOWN),
            dpadLeft = bit(BUTTON_DPAD_LEFT),
        )
    }

    /**
     * Apply an axis update from TC's wire format. Prefer [fullAxis] if
     * present (6-element float array, sticks in −1..1, triggers in 0..1).
     * Otherwise unpack [axisPacked]: 6 bytes, each holding 5 bits
     * (sign in bit 4, magnitude 0..15 in bits 0..3). Sticks reconstruct
     * as `(sign ? −1 : +1) * (mag / 15)`. Trigger bytes are direct 0..15
     * normalized to 0..1.
     *
     * Triggers stay in 0..1 (matches user-intuitive deadzone semantics
     * in [applyOutputMode]). Sticks stay in −1..1.
     */
    fun withAxes(axisPacked: Int, fullAxis: FloatArray?): ControllerState {
        if (fullAxis != null && fullAxis.size >= 6) {
            return copy(
                leftStickX = fullAxis[0],
                leftStickY = fullAxis[1],
                rightStickX = fullAxis[2],
                rightStickY = fullAxis[3],
                leftTrigger = fullAxis[4].coerceIn(0f, 1f),
                rightTrigger = fullAxis[5].coerceIn(0f, 1f),
            )
        }
        // Unpack 6 bytes from int: byte 0..5 each 5 bits used.
        // Per TC's ControllerRedstoneOutput.EncodeAxis bit layout (verified
        // by DBW's mixin code path): each axis byte sits at byte position
        // i, with bit 4 = sign, bits 0..3 = magnitude (0..15).
        fun byteAt(i: Int): Int = (axisPacked shr (i * 5)) and 0x1F
        fun stick(i: Int): Float {
            val b = byteAt(i)
            val mag = (b and 0x0F) / 15f
            return if (b and 0x10 != 0) -mag else mag
        }
        fun trigger(i: Int): Float = (byteAt(i) and 0x0F) / 15f
        return copy(
            leftStickX = stick(0),
            leftStickY = stick(1),
            rightStickX = stick(2),
            rightStickY = stick(3),
            leftTrigger = trigger(4),
            rightTrigger = trigger(5),
        )
    }

    companion object {
        val ZERO = ControllerState()
    }
}
