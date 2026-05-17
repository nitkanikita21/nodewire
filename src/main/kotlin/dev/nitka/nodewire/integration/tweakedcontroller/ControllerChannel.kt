package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinType

/**
 * One channel = one of TC's 25 user-configurable input slots, exactly
 * as the player sees them in TC's config GUI.
 *
 * TC's model (verified against `create-tweaked-controllers-1.20.1-1.2.6`
 * sources + `assets/.../lang/en_us.json`):
 *
 *   * 15 button slots (indices 0..14 in [ControlProfile.layout]).
 *     Each maps 1:1 to one bit in [ControllerRedstoneOutput.buttons].
 *   * 10 axis-half slots (indices 15..24). Stick axes are split into
 *     positive and negative halves — TC re-combines them into 4 signed
 *     bytes in [ControllerRedstoneOutput.axis] (bytes 0..3, bit 4 =
 *     sign, bits 0..3 = magnitude). Triggers (slots 23..24) are
 *     unsigned magnitudes in bytes 4..5.
 *
 * On top of TC's 25 raw slots we expose 3 *composite* convenience
 * channels — [LEFT_STICK], [RIGHT_STICK], [DPAD] — that combine
 * positive/negative axis halves (or 4 d-pad buttons) into a single 2D
 * value, because most graphs want "the stick as a vector", not four
 * separate slot reads.
 */
enum class ControllerChannelCategory {
    /**
     * Composite 2D channel ([LEFT_STICK], [RIGHT_STICK], [DPAD]).
     * Combines four raw inputs into a Vec2 in [−1, 1]² (sticks read
     * the signed bytes; d-pad subtracts negative buttons from positive).
     */
    COMPOSITE_VEC2,

    /** TC button slot — slots 0..14 in [ControlProfile.layout]. Output BOOL/REDSTONE. */
    BUTTON,

    /**
     * TC axis-half slot — slots 15..22 in [ControlProfile.layout]. A
     * stick axis split into positive or negative direction; output is
     * the magnitude 0..1. Output modes RAW/REDSTONE/BOOL.
     */
    AXIS_HALF,

    /**
     * TC trigger slot — slots 23..24 in [ControlProfile.layout]. An
     * unsigned 0..1 magnitude (no negative half because triggers only
     * go in one direction). Same output modes as [AXIS_HALF].
     */
    TRIGGER,
}

/**
 * The 25 TC slots + 3 composite convenience channels. Channel names
 * preserve TC's GUI labels so the player can match what they see in
 * TC's config menu directly.
 *
 * The enum [name] is the NBT serialization key — never reorder or
 * rename existing entries without a data-migration step.
 */
enum class ControllerChannel(
    val displayName: String,
    val category: ControllerChannelCategory,
) {
    // --- Composite convenience (Nodewire-only) ----------------------

    LEFT_STICK("Left Stick (composite)", ControllerChannelCategory.COMPOSITE_VEC2),
    RIGHT_STICK("Right Stick (composite)", ControllerChannelCategory.COMPOSITE_VEC2),
    DPAD("D-Pad (composite)", ControllerChannelCategory.COMPOSITE_VEC2),

    // --- TC button slots 0..14 --------------------------------------

    BUTTON_A("A Button", ControllerChannelCategory.BUTTON),
    BUTTON_B("B Button", ControllerChannelCategory.BUTTON),
    BUTTON_X("X Button", ControllerChannelCategory.BUTTON),
    BUTTON_Y("Y Button", ControllerChannelCategory.BUTTON),
    LEFT_SHOULDER("Left Shoulder", ControllerChannelCategory.BUTTON),
    RIGHT_SHOULDER("Right Shoulder", ControllerChannelCategory.BUTTON),
    BACK_BUTTON("Back Button", ControllerChannelCategory.BUTTON),
    START_BUTTON("Start Button", ControllerChannelCategory.BUTTON),
    GUIDE_BUTTON("Guide Button", ControllerChannelCategory.BUTTON),
    LEFT_JOYSTICK_CLICK("Left Joystick Click", ControllerChannelCategory.BUTTON),
    RIGHT_JOYSTICK_CLICK("Right Joystick Click", ControllerChannelCategory.BUTTON),
    DPAD_UP("D-Pad Up", ControllerChannelCategory.BUTTON),
    DPAD_RIGHT("D-Pad Right", ControllerChannelCategory.BUTTON),
    DPAD_DOWN("D-Pad Down", ControllerChannelCategory.BUTTON),
    DPAD_LEFT("D-Pad Left", ControllerChannelCategory.BUTTON),

    // --- TC axis-half slots 15..22 ----------------------------------
    // (names match TC's gui_gamepad_axis_0..7 from en_us.json)

    LEFT_X_POS("Left +X Axis", ControllerChannelCategory.AXIS_HALF),
    LEFT_X_NEG("Left -X Axis", ControllerChannelCategory.AXIS_HALF),
    LEFT_Y_POS("Left +Y Axis", ControllerChannelCategory.AXIS_HALF),
    LEFT_Y_NEG("Left -Y Axis", ControllerChannelCategory.AXIS_HALF),
    RIGHT_X_POS("Right +X Axis", ControllerChannelCategory.AXIS_HALF),
    RIGHT_X_NEG("Right -X Axis", ControllerChannelCategory.AXIS_HALF),
    RIGHT_Y_POS("Right +Y Axis", ControllerChannelCategory.AXIS_HALF),
    RIGHT_Y_NEG("Right -Y Axis", ControllerChannelCategory.AXIS_HALF),

    // --- TC trigger slots 23..24 ------------------------------------

    LEFT_TRIGGER("Left Trigger Axis", ControllerChannelCategory.TRIGGER),
    RIGHT_TRIGGER("Right Trigger Axis", ControllerChannelCategory.TRIGGER);

    companion object {
        fun fromName(name: String): ControllerChannel =
            entries.firstOrNull { it.name == name } ?: LEFT_STICK
    }
}

/**
 * How a channel reading projects onto output pins.
 *   * [VEC2_RAW] / [XY_RAW] / [XY_REDSTONE] / [MAGNITUDE_BOOL] — only
 *     valid for [ControllerChannelCategory.COMPOSITE_VEC2].
 *   * [RAW] / [REDSTONE] / [BOOL] — valid for all single-value
 *     categories ([BUTTON], [AXIS_HALF], [TRIGGER]).
 */
enum class ControllerOutputMode { VEC2_RAW, XY_RAW, XY_REDSTONE, MAGNITUDE_BOOL, RAW, REDSTONE, BOOL }

/**
 * Output modes valid for a given category. First entry = category default.
 */
fun allowedOutputModes(cat: ControllerChannelCategory): List<ControllerOutputMode> = when (cat) {
    ControllerChannelCategory.COMPOSITE_VEC2 -> listOf(
        ControllerOutputMode.VEC2_RAW,
        ControllerOutputMode.XY_RAW,
        ControllerOutputMode.XY_REDSTONE,
        ControllerOutputMode.MAGNITUDE_BOOL,
    )
    ControllerChannelCategory.BUTTON -> listOf(
        ControllerOutputMode.BOOL,
        ControllerOutputMode.REDSTONE,
    )
    ControllerChannelCategory.AXIS_HALF,
    ControllerChannelCategory.TRIGGER -> listOf(
        ControllerOutputMode.RAW,
        ControllerOutputMode.REDSTONE,
        ControllerOutputMode.BOOL,
    )
}

/**
 * Reads the channel's raw scalar value off the latest controller state.
 *
 *   * Composite sticks return `(x, y, false)` in [−1, 1]² (signed).
 *   * Single buttons return `(0, 0, pressed?)`.
 *   * Axis-halves return `(magnitude, 0, false)` in `[0, 1]`. The
 *     positive half fires only when the underlying signed axis is `> 0`;
 *     the negative half fires only when `< 0`. Magnitudes use
 *     `abs(value)` so each half maps cleanly into 0..1.
 *   * Triggers return `(magnitude, 0, false)` in `[0, 1]`.
 */
internal fun rawChannelValue(state: ControllerState, ch: ControllerChannel): Triple<Float, Float, Boolean> {
    fun pos(v: Float) = if (v > 0f) v else 0f
    fun neg(v: Float) = if (v < 0f) -v else 0f
    return when (ch) {
        // Composite sticks: full Vec2 with sign.
        ControllerChannel.LEFT_STICK -> Triple(state.leftStickX, state.leftStickY, false)
        ControllerChannel.RIGHT_STICK -> Triple(state.rightStickX, state.rightStickY, false)
        ControllerChannel.DPAD -> Triple(
            (if (state.dpadRight) 1f else 0f) - (if (state.dpadLeft) 1f else 0f),
            (if (state.dpadUp) 1f else 0f) - (if (state.dpadDown) 1f else 0f),
            false,
        )

        // Buttons.
        ControllerChannel.BUTTON_A -> Triple(0f, 0f, state.buttonA)
        ControllerChannel.BUTTON_B -> Triple(0f, 0f, state.buttonB)
        ControllerChannel.BUTTON_X -> Triple(0f, 0f, state.buttonX)
        ControllerChannel.BUTTON_Y -> Triple(0f, 0f, state.buttonY)
        ControllerChannel.LEFT_SHOULDER -> Triple(0f, 0f, state.leftBumper)
        ControllerChannel.RIGHT_SHOULDER -> Triple(0f, 0f, state.rightBumper)
        ControllerChannel.BACK_BUTTON -> Triple(0f, 0f, state.back)
        ControllerChannel.START_BUTTON -> Triple(0f, 0f, state.start)
        ControllerChannel.GUIDE_BUTTON -> Triple(0f, 0f, false) // ControllerState doesn't track guide; reserved.
        ControllerChannel.LEFT_JOYSTICK_CLICK -> Triple(0f, 0f, state.leftStickClick)
        ControllerChannel.RIGHT_JOYSTICK_CLICK -> Triple(0f, 0f, state.rightStickClick)
        ControllerChannel.DPAD_UP -> Triple(0f, 0f, state.dpadUp)
        ControllerChannel.DPAD_RIGHT -> Triple(0f, 0f, state.dpadRight)
        ControllerChannel.DPAD_DOWN -> Triple(0f, 0f, state.dpadDown)
        ControllerChannel.DPAD_LEFT -> Triple(0f, 0f, state.dpadLeft)

        // Stick axis halves — magnitude in 0..1, gated on sign.
        ControllerChannel.LEFT_X_POS -> Triple(pos(state.leftStickX), 0f, false)
        ControllerChannel.LEFT_X_NEG -> Triple(neg(state.leftStickX), 0f, false)
        ControllerChannel.LEFT_Y_POS -> Triple(pos(state.leftStickY), 0f, false)
        ControllerChannel.LEFT_Y_NEG -> Triple(neg(state.leftStickY), 0f, false)
        ControllerChannel.RIGHT_X_POS -> Triple(pos(state.rightStickX), 0f, false)
        ControllerChannel.RIGHT_X_NEG -> Triple(neg(state.rightStickX), 0f, false)
        ControllerChannel.RIGHT_Y_POS -> Triple(pos(state.rightStickY), 0f, false)
        ControllerChannel.RIGHT_Y_NEG -> Triple(neg(state.rightStickY), 0f, false)

        // Triggers — already 0..1 unsigned.
        ControllerChannel.LEFT_TRIGGER -> Triple(state.leftTrigger, 0f, false)
        ControllerChannel.RIGHT_TRIGGER -> Triple(state.rightTrigger, 0f, false)
    }
}

/**
 * Project a channel reading onto output pins per [mode].
 *
 *   * COMPOSITE_VEC2 mode set ([VEC2_RAW] etc.) — only meaningful for
 *     composite-vec2 channels; uses both `(x, y)` components.
 *   * Single-value modes ([RAW], [REDSTONE], [BOOL]) — read `raw.first`
 *     (for axes / triggers) or `raw.third` (for buttons), and project.
 *
 * Deadzone applies to BOOL conversions and to MAGNITUDE_BOOL.
 * Invert flips signs of axes / triggers / composite stick reads.
 */
fun applyOutputMode(
    state: ControllerState,
    channel: ControllerChannel,
    mode: ControllerOutputMode,
    deadzone: Float,
    invert: Boolean,
): Map<String, PinValue> {
    val raw = rawChannelValue(state, channel)
    val sign = if (invert) -1f else 1f
    return when (mode) {
        ControllerOutputMode.VEC2_RAW ->
            mapOf("xy" to PinValue.Vec2(raw.first * sign, raw.second * sign))

        ControllerOutputMode.XY_RAW -> mapOf(
            "x" to PinValue.Float(raw.first * sign),
            "y" to PinValue.Float(raw.second * sign),
        )

        ControllerOutputMode.XY_REDSTONE -> mapOf(
            "x" to PinValue.Redstone(signedAxisToRedstone(raw.first * sign)),
            "y" to PinValue.Redstone(signedAxisToRedstone(raw.second * sign)),
        )

        ControllerOutputMode.MAGNITUDE_BOOL -> {
            val mag = kotlin.math.sqrt(raw.first * raw.first + raw.second * raw.second)
            mapOf("pressed" to PinValue.Bool(mag > deadzone))
        }

        ControllerOutputMode.RAW -> mapOf("value" to PinValue.Float(raw.first * sign))

        ControllerOutputMode.REDSTONE -> mapOf(
            "value" to PinValue.Redstone(
                when (channel.category) {
                    ControllerChannelCategory.BUTTON -> if (raw.third) 15 else 0
                    // Axis halves and triggers are 0..1 magnitudes → linear 0..15.
                    ControllerChannelCategory.AXIS_HALF,
                    ControllerChannelCategory.TRIGGER -> unitToRedstone(raw.first * sign)
                    // Composite stick reduced to single redstone — magnitude of vector.
                    ControllerChannelCategory.COMPOSITE_VEC2 ->
                        unitToRedstone(kotlin.math.sqrt(raw.first * raw.first + raw.second * raw.second))
                }
            )
        )

        ControllerOutputMode.BOOL -> mapOf(
            "pressed" to PinValue.Bool(
                when (channel.category) {
                    ControllerChannelCategory.BUTTON -> raw.third
                    ControllerChannelCategory.AXIS_HALF,
                    ControllerChannelCategory.TRIGGER -> (raw.first * sign) > deadzone
                    ControllerChannelCategory.COMPOSITE_VEC2 ->
                        kotlin.math.sqrt(raw.first * raw.first + raw.second * raw.second) > deadzone
                }
            )
        )
    }
}

/** Map signed axis −1..1 to redstone 0..15 centered at 7 (used by composite sticks). */
private fun signedAxisToRedstone(axis: Float): Int =
    (((axis.coerceIn(-1f, 1f) + 1f) * 0.5f) * 15f).toInt().coerceIn(0, 15)

/** Map unsigned magnitude 0..1 to redstone 0..15 (axis halves, triggers). */
private fun unitToRedstone(v: Float): Int =
    (v.coerceIn(0f, 1f) * 15f).toInt().coerceIn(0, 15)

/**
 * Output pin list for a (channel, mode). Used by both the NodeType
 * registration and the EditorState reshape mutator so they never drift apart.
 */
fun pinsForControllerInput(
    channel: ControllerChannel,
    mode: ControllerOutputMode,
): List<dev.nitka.nodewire.graph.Pin> = when (mode) {
    ControllerOutputMode.VEC2_RAW -> listOf(Pin("xy", "XY", PinType.VEC2))
    ControllerOutputMode.XY_RAW -> listOf(
        Pin("x", "X", PinType.FLOAT),
        Pin("y", "Y", PinType.FLOAT),
    )
    ControllerOutputMode.XY_REDSTONE -> listOf(
        Pin("x", "X", PinType.REDSTONE),
        Pin("y", "Y", PinType.REDSTONE),
    )
    ControllerOutputMode.MAGNITUDE_BOOL -> listOf(Pin("pressed", "Pressed", PinType.BOOL))
    ControllerOutputMode.RAW -> listOf(Pin("value", "Value", PinType.FLOAT))
    ControllerOutputMode.REDSTONE -> listOf(Pin("value", "Signal", PinType.REDSTONE))
    ControllerOutputMode.BOOL -> listOf(Pin("pressed", "Pressed", PinType.BOOL))
}
