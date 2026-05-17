package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinType

/**
 * Logical category a channel belongs to. Drives which [OutputMode]
 * variants are valid and what the raw input shape looks like:
 *   * Stick / DPadComposite — produce a 2D vector
 *   * Trigger — produce a 0..1 float
 *   * Button / DPadSingle — produce a bool
 */
enum class ControllerChannelCategory { STICK, TRIGGER, BUTTON, DPAD_SINGLE, DPAD_COMPOSITE }

/**
 * Every input source on the gamepad. Names are stable (serialized to
 * NBT by [name]); adding new entries is forward-compatible.
 *
 * [displayName] is the human-readable label for UI.
 */
enum class ControllerChannel(
    val displayName: String,
    val category: ControllerChannelCategory,
) {
    LEFT_STICK("Left Stick", ControllerChannelCategory.STICK),
    RIGHT_STICK("Right Stick", ControllerChannelCategory.STICK),
    LEFT_TRIGGER("Left Trigger", ControllerChannelCategory.TRIGGER),
    RIGHT_TRIGGER("Right Trigger", ControllerChannelCategory.TRIGGER),
    BUTTON_A("Button A", ControllerChannelCategory.BUTTON),
    BUTTON_B("Button B", ControllerChannelCategory.BUTTON),
    BUTTON_X("Button X", ControllerChannelCategory.BUTTON),
    BUTTON_Y("Button Y", ControllerChannelCategory.BUTTON),
    LEFT_BUMPER("Left Bumper", ControllerChannelCategory.BUTTON),
    RIGHT_BUMPER("Right Bumper", ControllerChannelCategory.BUTTON),
    LEFT_STICK_CLICK("Left Stick Click", ControllerChannelCategory.BUTTON),
    RIGHT_STICK_CLICK("Right Stick Click", ControllerChannelCategory.BUTTON),
    START("Start", ControllerChannelCategory.BUTTON),
    BACK("Back", ControllerChannelCategory.BUTTON),
    DPAD_UP("D-Pad Up", ControllerChannelCategory.DPAD_SINGLE),
    DPAD_DOWN("D-Pad Down", ControllerChannelCategory.DPAD_SINGLE),
    DPAD_LEFT("D-Pad Left", ControllerChannelCategory.DPAD_SINGLE),
    DPAD_RIGHT("D-Pad Right", ControllerChannelCategory.DPAD_SINGLE),
    DPAD("D-Pad", ControllerChannelCategory.DPAD_COMPOSITE);

    companion object {
        fun fromName(name: String): ControllerChannel =
            entries.firstOrNull { it.name == name } ?: LEFT_STICK
    }
}

/**
 * How the channel's raw value should be projected to one or more pin
 * outputs. Valid variants depend on the channel's [ControllerChannelCategory].
 */
enum class ControllerOutputMode { VEC2_RAW, XY_RAW, XY_REDSTONE, MAGNITUDE_BOOL, RAW, REDSTONE, BOOL }

/**
 * Which output modes are valid for a given category. The first entry
 * in each list is the default when the category is selected.
 */
fun allowedOutputModes(cat: ControllerChannelCategory): List<ControllerOutputMode> = when (cat) {
    ControllerChannelCategory.STICK,
    ControllerChannelCategory.DPAD_COMPOSITE -> listOf(
        ControllerOutputMode.VEC2_RAW,
        ControllerOutputMode.XY_RAW,
        ControllerOutputMode.XY_REDSTONE,
        ControllerOutputMode.MAGNITUDE_BOOL,
    )
    ControllerChannelCategory.TRIGGER -> listOf(
        ControllerOutputMode.RAW,
        ControllerOutputMode.REDSTONE,
        ControllerOutputMode.BOOL,
    )
    ControllerChannelCategory.BUTTON,
    ControllerChannelCategory.DPAD_SINGLE -> listOf(
        ControllerOutputMode.BOOL,
        ControllerOutputMode.REDSTONE,
    )
}

/**
 * Raw channel value read off [state], normalized to ControllerState
 * conventions. Returns a Triple of (xOrAxis, yOrZero, boolOrZero)
 * because different categories use different slots — [applyOutputMode]
 * picks the right one.
 */
internal fun rawChannelValue(state: ControllerState, ch: ControllerChannel): Triple<Float, Float, Boolean> {
    return when (ch) {
        ControllerChannel.LEFT_STICK -> Triple(state.leftStickX, state.leftStickY, false)
        ControllerChannel.RIGHT_STICK -> Triple(state.rightStickX, state.rightStickY, false)
        ControllerChannel.LEFT_TRIGGER -> Triple(state.leftTrigger, 0f, false)
        ControllerChannel.RIGHT_TRIGGER -> Triple(state.rightTrigger, 0f, false)
        ControllerChannel.BUTTON_A -> Triple(0f, 0f, state.buttonA)
        ControllerChannel.BUTTON_B -> Triple(0f, 0f, state.buttonB)
        ControllerChannel.BUTTON_X -> Triple(0f, 0f, state.buttonX)
        ControllerChannel.BUTTON_Y -> Triple(0f, 0f, state.buttonY)
        ControllerChannel.LEFT_BUMPER -> Triple(0f, 0f, state.leftBumper)
        ControllerChannel.RIGHT_BUMPER -> Triple(0f, 0f, state.rightBumper)
        ControllerChannel.LEFT_STICK_CLICK -> Triple(0f, 0f, state.leftStickClick)
        ControllerChannel.RIGHT_STICK_CLICK -> Triple(0f, 0f, state.rightStickClick)
        ControllerChannel.START -> Triple(0f, 0f, state.start)
        ControllerChannel.BACK -> Triple(0f, 0f, state.back)
        ControllerChannel.DPAD_UP -> Triple(0f, 0f, state.dpadUp)
        ControllerChannel.DPAD_DOWN -> Triple(0f, 0f, state.dpadDown)
        ControllerChannel.DPAD_LEFT -> Triple(0f, 0f, state.dpadLeft)
        ControllerChannel.DPAD_RIGHT -> Triple(0f, 0f, state.dpadRight)
        ControllerChannel.DPAD -> {
            val x = (if (state.dpadRight) 1f else 0f) - (if (state.dpadLeft) 1f else 0f)
            val y = (if (state.dpadUp) 1f else 0f) - (if (state.dpadDown) 1f else 0f)
            Triple(x, y, false)
        }
    }
}

/**
 * Project a controller channel reading into the output pins of a
 * `controller_input` node. Returns a map keyed by pin id matching what
 * [pinsForControllerInput] produces for the same (channel, mode).
 *
 * Deadzone applies to Stick / DPadComposite magnitude (for
 * [ControllerOutputMode.MAGNITUDE_BOOL]) and to Trigger value (for
 * [ControllerOutputMode.BOOL]).
 *
 * Invert flips sign of Trigger raw value and of stick axes when used
 * via [ControllerOutputMode.XY_RAW] / [ControllerOutputMode.XY_REDSTONE]
 * (it's typically used when "up = positive" doesn't match the user's
 * preferred mapping for a particular game-side use).
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
            "x" to PinValue.Redstone(axisToRedstone(raw.first * sign)),
            "y" to PinValue.Redstone(axisToRedstone(raw.second * sign)),
        )

        ControllerOutputMode.MAGNITUDE_BOOL -> {
            val mag = kotlin.math.sqrt(raw.first * raw.first + raw.second * raw.second)
            mapOf("pressed" to PinValue.Bool(mag > deadzone))
        }

        ControllerOutputMode.RAW ->
            mapOf("value" to PinValue.Float(raw.first * sign))

        ControllerOutputMode.REDSTONE ->
            mapOf("value" to PinValue.Redstone(unitToRedstone(raw.first * sign)))

        ControllerOutputMode.BOOL ->
            mapOf("pressed" to PinValue.Bool(
                if (channel.category == ControllerChannelCategory.TRIGGER)
                    (raw.first * sign) > deadzone
                else raw.third,
            ))
    }
}

/** Map [−1, 1] axis to [0, 15] redstone. Center axis (0) → 7. */
private fun axisToRedstone(axis: Float): Int =
    (((axis.coerceIn(-1f, 1f) + 1f) * 0.5f) * 15f).toInt().coerceIn(0, 15)

/** Map [0, 1] trigger to [0, 15] redstone. */
private fun unitToRedstone(v: Float): Int =
    (v.coerceIn(0f, 1f) * 15f).toInt().coerceIn(0, 15)

/**
 * Output pin list for a given (channel, mode). Used by both the
 * NodeType registration and the EditorState reshape mutator so they
 * never drift apart.
 */
fun pinsForControllerInput(
    channel: ControllerChannel,
    mode: ControllerOutputMode,
): List<dev.nitka.nodewire.graph.Pin> {
    return when (mode) {
        ControllerOutputMode.VEC2_RAW -> listOf(
            Pin("xy", "XY", PinType.VEC2),
        )
        ControllerOutputMode.XY_RAW -> listOf(
            Pin("x", "X", PinType.FLOAT),
            Pin("y", "Y", PinType.FLOAT),
        )
        ControllerOutputMode.XY_REDSTONE -> listOf(
            Pin("x", "X", PinType.REDSTONE),
            Pin("y", "Y", PinType.REDSTONE),
        )
        ControllerOutputMode.MAGNITUDE_BOOL -> listOf(
            Pin("pressed", "Pressed", PinType.BOOL),
        )
        ControllerOutputMode.RAW -> listOf(
            Pin("value", "Value", PinType.FLOAT),
        )
        ControllerOutputMode.REDSTONE -> listOf(
            Pin("value", "Signal", PinType.REDSTONE),
        )
        ControllerOutputMode.BOOL -> listOf(
            Pin("pressed", "Pressed", PinType.BOOL),
        )
    }
}
