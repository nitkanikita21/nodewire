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
     * Apply pre-decoded button array. Position-to-name mapping matches
     * `TweakedLecternControllerBlockEntity.GetButton(int)`, which itself
     * reads `ControllerRedstoneOutput.buttons` after `DecodeButtons(short)`.
     */
    fun withButtonArray(buttons: BooleanArray): ControllerState {
        fun b(i: Int) = buttons.getOrElse(i) { false }
        return copy(
            buttonA = b(BUTTON_A),
            buttonB = b(BUTTON_B),
            buttonX = b(BUTTON_X),
            buttonY = b(BUTTON_Y),
            leftBumper = b(BUTTON_LB),
            rightBumper = b(BUTTON_RB),
            back = b(BUTTON_BACK),
            start = b(BUTTON_START),
            leftStickClick = b(BUTTON_L_STICK_CLICK),
            rightStickClick = b(BUTTON_R_STICK_CLICK),
            dpadUp = b(BUTTON_DPAD_UP),
            dpadRight = b(BUTTON_DPAD_RIGHT),
            dpadDown = b(BUTTON_DPAD_DOWN),
            dpadLeft = b(BUTTON_DPAD_LEFT),
        )
    }

    /**
     * Apply pre-decoded axis bytes. `axisBytes` mirrors TC's `Byte[6]`
     * shape after `ControllerRedstoneOutput.DecodeAxis(int)`:
     *   * bytes 0..3 — signed stick axes: bit-4 is sign, bits 0..3 are
     *     magnitude 0..15. Order is L_X, L_Y, R_X, R_Y.
     *   * bytes 4..5 — unsigned trigger magnitudes 0..15 (LT, RT).
     *
     * Prefer [fullAxis] when present (TC's high-precision float array
     * of length ≥ 6: sticks in −1..1, triggers in 0..1).
     */
    fun withAxisArray(axisBytes: ByteArray, fullAxis: FloatArray?): ControllerState {
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
        fun stickFloat(b: Byte): Float {
            val raw = b.toInt() and 0xFF
            val mag = (raw and 0x0F) / 15f
            return if (raw and 0x10 != 0) -mag else mag
        }
        fun triggerFloat(b: Byte): Float = ((b.toInt() and 0x0F)) / 15f
        return copy(
            leftStickX = stickFloat(axisBytes.getOrElse(0) { 0 }),
            leftStickY = stickFloat(axisBytes.getOrElse(1) { 0 }),
            rightStickX = stickFloat(axisBytes.getOrElse(2) { 0 }),
            rightStickY = stickFloat(axisBytes.getOrElse(3) { 0 }),
            leftTrigger = triggerFloat(axisBytes.getOrElse(4) { 0 }),
            rightTrigger = triggerFloat(axisBytes.getOrElse(5) { 0 }),
        )
    }

    companion object {
        val ZERO = ControllerState()
    }
}
