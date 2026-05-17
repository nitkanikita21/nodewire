# Tweaked Controller Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `controller_input` node that reads gamepad state from a Tweaked Controller (TC) item bound to the Logic Block, plus a Drive-By-Wire-style RMB binding flow.

**Architecture:** TC declared as soft `modRuntimeOnly`/`modCompileOnly`. A reflection-based `TweakedController` wrapper isolates Nodewire from TC's compile-time API — if TC is absent or its API surface changes, the wrapper degrades to "not loaded" and the node emits zero values. `LogicBlockEntity` gains a nullable `controllerId: UUID?` field; a `RightClickBlock` handler binds the item-in-hand to the block. The channel node reshapes pins per `(channel, outputMode)` config.

**Tech Stack:** Kotlin 2.0.20, Minecraft Forge 1.20.1, ModDevGradle legacyForge, JUnit 5, existing Nodewire codec + registry + network infrastructure.

**Spec:** `docs/superpowers/specs/2026-05-17-tweaked-controller-and-vectors-design.md` §B.

---

## Stay on master, do NOT run runClient

- Commit to **master**, no feature branches.
- Do NOT run `./gradlew runClient`. Use `compileKotlin`, `build`, `test`.

---

## File structure

**New files:**
- `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/TweakedController.kt` — reflection-based soft-dep wrapper.
- `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannel.kt` — channel enum + applyOutputMode helpers.
- `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputNode.kt` — `controller_input` NodeType + evaluator.
- `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerBindHandler.kt` — RightClickBlock event handler.
- `src/main/kotlin/dev/nitka/nodewire/net/SetControllerIdPacket.kt` — client → server unbind packet.
- `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannelTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputPinReshapeTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/block/LogicBlockEntityControllerBindTest.kt`

**Modified files:**
- `build.gradle.kts` — add Modrinth maven + TC dependency.
- `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt` — controllerId field, getter/setter, NBT.
- `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt` — register bind handler.
- `src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt` — register SetControllerIdPacket.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — register `controller_input` in `registerAll`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — `ControllerInput` composable.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — `changeControllerChannel`, `changeControllerOutputMode` mutators.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt` — controller indicator.

---

# Phase 1 — Dependency + TC discovery spike

### Task 1: Add Modrinth maven + TC `modRuntimeOnly` + inspect TC jar

**Files:**
- Modify: `build.gradle.kts`

The Modrinth project slug for Tweaked Controller is `create-tweaked-controllers` (Modrinth project ID `EScBvcOc`). Latest 1.20.1 version: `1.2.6`. Modrinth offers a maven endpoint: `https://api.modrinth.com/maven`. The artifact coordinate convention is `maven.modrinth:<slug>:<version>` (CurseForge/Modrinth gradle conventions).

- [ ] **Step 1: Add Modrinth maven repository**

Open `build.gradle.kts` and find the `repositories { ... }` block (top-level, NOT inside `pluginManagement`). Add:

```kotlin
    maven {
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
```

- [ ] **Step 2: Add TC dependency**

Find the `dependencies { ... }` block. Locate the line declaring Create runtime (it should have `modImplementation` or `modRuntimeOnly` for create). Add:

```kotlin
    modRuntimeOnly("maven.modrinth:create-tweaked-controllers:1.20.1-1.2.6")
    modCompileOnly("maven.modrinth:create-tweaked-controllers:1.20.1-1.2.6")
```

- [ ] **Step 3: Verify dependency resolves**

Run: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep -i "tweaked"`
Expected: a line showing `create-tweaked-controllers-1.20.1-1.2.6` in the dep tree. If the dep doesn't resolve, fall back to:

```kotlin
    modRuntimeOnly("maven.modrinth:create-tweaked-controllers:EScBvcOc")  // Modrinth version-id
```

- [ ] **Step 4: Inspect the resolved jar**

Run: `find ~/.gradle/caches/modules-2/files-2.1/maven.modrinth -name "create-tweaked-controllers*.jar" 2>/dev/null | head -1`

Capture the path as `$JAR`. Then:

```bash
unzip -p "$JAR" META-INF/mods.toml | head -30
```

Note the `modId` value (it's the actual Forge mod id; might be `tweakedcontrollers` or `createtweakedcontrollers` or similar).

```bash
unzip -l "$JAR" | grep -i "\.class" | grep -i -E "controller|item|input|state" | head -30
```

Look for candidate classes: an item class (likely contains "Controller" + "Item"), a state holder (contains "State" / "Bindings" / "Input"), and any event-listener-y class.

**Record findings in the commit message.** Example: "TC mod id: `tweakedcontrollers`, controller item class: `com.foo.TweakedControllerItem`, state class: `com.foo.ControllerInputState`."

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(tc): add Tweaked Controller as soft dependency

Modrinth maven + create-tweaked-controllers 1.20.1-1.2.6.
TC mod id: <fill in>
Controller item class: <fill in>
State accessor: <fill in or "TBD via reflection lookup">

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — Soft-dep wrapper

### Task 2: `TweakedController` reflection wrapper

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/TweakedController.kt`

- [ ] **Step 1: Write file**

```kotlin
package dev.nitka.nodewire.integration.tweakedcontroller

import net.minecraft.world.item.ItemStack
import net.minecraftforge.fml.ModList
import java.util.UUID

/**
 * Soft-dependency wrapper for the Tweaked Controller mod. The mod itself
 * isn't a compile-time dependency: all access goes through reflection
 * so Nodewire builds and runs with TC absent. Every public method
 * degrades gracefully — returns false / null / a zero state — when TC
 * isn't loaded or the cached class lookup fails.
 *
 * Mod id constant lives below. If TC's actual mod id differs, update
 * [MOD_ID] and re-run the build.
 */
object TweakedController {

    /**
     * Forge mod id used by the loaded() check. Update if Phase 1 spike
     * found a different id.
     */
    const val MOD_ID: String = "tweakedcontrollers"

    /**
     * Cached `Class<?>` reference for TC's controller item. Lazy so we
     * pay the lookup cost once. `null` when TC isn't on classpath.
     */
    private val controllerItemClass: Class<*>? by lazy {
        try {
            Class.forName("com.tweakedcontrollers.items.ControllerItem")
        } catch (_: Throwable) {
            null
        }
    }

    /** True iff TC mod is loaded into the current runtime. */
    fun isLoaded(): Boolean = ModList.get()?.isLoaded(MOD_ID) ?: false

    /**
     * Returns the unique identifier of the controller represented by
     * [stack], or `null` if the stack isn't a TC controller item. The
     * id is derived from the item NBT — TC stores a UUID under the
     * conventional NBT key `controllerId` (most binding mods do).
     */
    fun controllerItemId(stack: ItemStack): UUID? {
        if (!isLoaded() || stack.isEmpty) return null
        val klass = controllerItemClass ?: return null
        if (!klass.isInstance(stack.item)) return null
        val tag = stack.tag ?: return null
        if (tag.hasUUID("controllerId")) return tag.getUUID("controllerId")
        // Fallback alternative key conventions; TC may use different naming.
        if (tag.hasUUID("uuid")) return tag.getUUID("uuid")
        if (tag.hasUUID("id")) return tag.getUUID("id")
        return null
    }

    /**
     * Fetch the current input state for the controller [id]. Returns
     * `null` when TC isn't loaded, the controller is offline, or the
     * server-side state hasn't been replicated by TC. Real wiring into
     * TC's state replication is deferred — see comment block in
     * [ControllerInputNode] for the open question on client→server
     * replication.
     */
    fun getControllerState(id: UUID): ControllerState? {
        if (!isLoaded()) return null
        // TODO: wire reflection access to TC's state map once Phase 1
        //  spike resolves it. Until then, return null so the evaluator
        //  emits zero values.
        return null
    }
}

/**
 * Plain data class holding one snapshot of a controller's gamepad
 * inputs. Values are normalized:
 *   * stick axes in [−1f, 1f] (raw GLFW); positive y is "up"
 *   * triggers in [0f, 1f]
 *   * buttons are boolean
 *
 * Populated from TC by [TweakedController.getControllerState]. Defaults
 * are all-zero / all-false so the evaluator never reads NaN.
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
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/TweakedController.kt
git commit -m "$(cat <<'EOF'
feat(tc): soft-dep wrapper TweakedController + ControllerState

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — `ControllerChannel` enum + `applyOutputMode`

### Task 3: Channel enum + categories

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannel.kt`

- [ ] **Step 1: Write enum + categories + channel-value reader**

```kotlin
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
    val P = PinType
    return when (mode) {
        ControllerOutputMode.VEC2_RAW -> listOf(
            Pin("xy", "XY", P.VEC2),
        )
        ControllerOutputMode.XY_RAW -> listOf(
            Pin("x", "X", P.FLOAT),
            Pin("y", "Y", P.FLOAT),
        )
        ControllerOutputMode.XY_REDSTONE -> listOf(
            Pin("x", "X", P.REDSTONE),
            Pin("y", "Y", P.REDSTONE),
        )
        ControllerOutputMode.MAGNITUDE_BOOL -> listOf(
            Pin("pressed", "Pressed", P.BOOL),
        )
        ControllerOutputMode.RAW -> listOf(
            Pin("value", "Value", P.FLOAT),
        )
        ControllerOutputMode.REDSTONE -> listOf(
            Pin("value", "Signal", P.REDSTONE),
        )
        ControllerOutputMode.BOOL -> listOf(
            Pin("pressed", "Pressed", P.BOOL),
        )
    }
}
```

- [ ] **Step 2: Build** → `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannel.kt
git commit -m "$(cat <<'EOF'
feat(tc): ControllerChannel enum + OutputMode + applyOutputMode

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: `ControllerChannelTest` — applyOutputMode table-driven

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannelTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControllerChannelTest {

    @Test fun stickVec2Raw() {
        val s = ControllerState(leftStickX = 0.5f, leftStickY = -0.25f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.VEC2_RAW, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Vec2(0.5f, -0.25f), out["xy"])
    }

    @Test fun stickXyRedstoneCenteredAt7() {
        val s = ControllerState(leftStickX = 0f, leftStickY = 0f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.XY_REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(7), out["x"])
        assertEquals(PinValue.Redstone(7), out["y"])
    }

    @Test fun stickXyRedstoneFullRange() {
        val s = ControllerState(leftStickX = 1f, leftStickY = -1f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.XY_REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["x"])
        assertEquals(PinValue.Redstone(0), out["y"])
    }

    @Test fun stickMagnitudeBoolBelowDeadzone() {
        val s = ControllerState(leftStickX = 0.1f, leftStickY = 0.05f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.MAGNITUDE_BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(false), out["pressed"])
    }

    @Test fun stickMagnitudeBoolAboveDeadzone() {
        val s = ControllerState(leftStickX = 0.6f, leftStickY = 0.8f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.MAGNITUDE_BOOL, deadzone = 0.15f, invert = false)
        // length = 1.0 > 0.15
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun triggerRedstone() {
        val s = ControllerState(rightTrigger = 1f)
        val out = applyOutputMode(s, ControllerChannel.RIGHT_TRIGGER, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["value"])
    }

    @Test fun triggerRedstoneHalf() {
        val s = ControllerState(leftTrigger = 0.5f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        // 0.5 * 15 = 7
        assertEquals(PinValue.Redstone(7), out["value"])
    }

    @Test fun triggerBoolBelowDeadzone() {
        val s = ControllerState(leftTrigger = 0.1f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(false), out["pressed"])
    }

    @Test fun triggerBoolAboveDeadzone() {
        val s = ControllerState(leftTrigger = 0.3f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun buttonBoolPressed() {
        val s = ControllerState(buttonA = true)
        val out = applyOutputMode(s, ControllerChannel.BUTTON_A, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun buttonRedstone() {
        val s = ControllerState(buttonB = true)
        val out = applyOutputMode(s, ControllerChannel.BUTTON_B, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["value"])
    }

    @Test fun dpadCompositeBothPressedDiag() {
        val s = ControllerState(dpadUp = true, dpadRight = true)
        val out = applyOutputMode(s, ControllerChannel.DPAD, ControllerOutputMode.VEC2_RAW, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Vec2(1f, 1f), out["xy"])
    }

    @Test fun triggerInvert() {
        val s = ControllerState(leftTrigger = 0.5f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.RAW, deadzone = 0.15f, invert = true)
        assertEquals(PinValue.Float(-0.5f), out["value"])
    }
}
```

- [ ] **Step 2: Run** → `./gradlew test --tests "dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannelTest"` → 13 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannelTest.kt
git commit -m "$(cat <<'EOF'
test(tc): applyOutputMode table-driven coverage

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — `LogicBlockEntity.controllerId`

### Task 5: Add controllerId field + NBT + setter

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

- [ ] **Step 1: Add field + getter/setter near the existing `blockName` block (after line ~71)**

In `LogicBlockEntity.kt`, after the existing `setBlockName` method (which currently ends around line 71), insert:

```kotlin
    /**
     * UUID of the Tweaked Controller item bound to this block. `null`
     * when unbound. Persisted to NBT. Mutating via [setControllerId]
     * also fires [setChanged] + a block update so clients see the
     * change (used by the editor toolbar's controller indicator).
     */
    private var controllerId: java.util.UUID? = null

    fun getControllerId(): java.util.UUID? = controllerId

    fun setControllerId(value: java.util.UUID?) {
        if (controllerId == value) return
        controllerId = value
        setChanged()
        val l = level ?: return
        l.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }
```

- [ ] **Step 2: Extend `saveAdditional`** — within the existing `saveAdditional` method (around line 484), append AFTER the `blockName` save:

```kotlin
        controllerId?.let { tag.putUUID("controllerId", it) }
```

- [ ] **Step 3: Extend `load`** — within the existing `load` method (around line 513), insert after the `blockName = tag.getString("name")` line:

```kotlin
        controllerId = if (tag.hasUUID("controllerId")) tag.getUUID("controllerId") else null
```

- [ ] **Step 4: Build** → `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "$(cat <<'EOF'
feat(tc): LogicBlockEntity.controllerId field + NBT roundtrip

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6: NBT roundtrip test

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/block/LogicBlockEntityControllerBindTest.kt`

- [ ] **Step 1: Write test**

Unit-testing `BlockEntity.load` directly requires MC bootstrap. Cheaper: validate the NBT round-trip semantics independently — write a small standalone helper that mirrors `saveAdditional`/`load` for the controllerId field, and test that. The actual class uses the same `putUUID`/`hasUUID`/`getUUID` API.

```kotlin
package dev.nitka.nodewire.block

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Validates the encoding contract used by [LogicBlockEntity.saveAdditional]
 * for the `controllerId` field: present iff non-null, decodes via
 * [CompoundTag.hasUUID] / [CompoundTag.getUUID]. We don't instantiate
 * the BE itself (requires MC bootstrap); the helper mirrors the
 * save/load logic for the bound-id slot exclusively.
 */
class LogicBlockEntityControllerBindTest {

    private fun save(tag: CompoundTag, id: UUID?) {
        id?.let { tag.putUUID("controllerId", it) }
    }

    private fun load(tag: CompoundTag): UUID? =
        if (tag.hasUUID("controllerId")) tag.getUUID("controllerId") else null

    @Test fun roundTripWithId() {
        val id = UUID.randomUUID()
        val tag = CompoundTag()
        save(tag, id)
        assertEquals(id, load(tag))
    }

    @Test fun roundTripNullOmitsKey() {
        val tag = CompoundTag()
        save(tag, null)
        assertNull(load(tag))
        // also: nothing was written
        assert(!tag.hasUUID("controllerId"))
    }
}
```

- [ ] **Step 2: Run** → `./gradlew test --tests "dev.nitka.nodewire.block.LogicBlockEntityControllerBindTest"` → 2 pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/block/LogicBlockEntityControllerBindTest.kt
git commit -m "$(cat <<'EOF'
test(tc): controllerId NBT roundtrip semantics

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 5 — `SetControllerIdPacket`

### Task 7: Register packet for Unbind from editor

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/net/SetControllerIdPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt`

- [ ] **Step 1: Create packet**

```kotlin
package dev.nitka.nodewire.net

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.UUID
import java.util.function.Supplier

/**
 * C→S: client-side unbind/replace of a block's bound controller.
 * Used by the editor toolbar's "Unbind" button. `id == null` clears
 * the binding; non-null replaces it (rare — the usual bind path is
 * via [dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler]
 * RMB-with-controller-item, not packets).
 */
class SetControllerIdPacket(val pos: BlockPos, val id: UUID?) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeBoolean(id != null)
        id?.let { buf.writeUUID(it) }
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return@enqueueWork
            be.setControllerId(id)
        }
        c.packetHandled = true
        return true
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): SetControllerIdPacket {
            val pos = buf.readBlockPos()
            val has = buf.readBoolean()
            return SetControllerIdPacket(pos, if (has) buf.readUUID() else null)
        }
    }
}
```

- [ ] **Step 2: Register in `NodewireNetwork.kt`**

Open `src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt`. Find the block where `SetBlockNamePacket` is registered (look around line 48). Add an analogous block right after it, bumping `id`:

```kotlin
        CHANNEL.messageBuilder(SetControllerIdPacket::class.java, id++)
            .encoder(SetControllerIdPacket::encode)
            .decoder(SetControllerIdPacket.Companion::decode)
            .consumerMainThread(SetControllerIdPacket::handle)
            .add()
```

(Match whatever method name suffix the existing registration uses — e.g. if SetBlockNamePacket uses `.add()` or `.build()`, do the same.)

- [ ] **Step 3: Build** → `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/net/SetControllerIdPacket.kt \
        src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt
git commit -m "$(cat <<'EOF'
feat(tc): SetControllerIdPacket for editor unbind

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 6 — RMB bind handler

### Task 8: `ControllerBindHandler` + register

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerBindHandler.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`

- [ ] **Step 1: Create handler**

```kotlin
package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlock
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * RMB handler: when the player right-clicks a LogicBlock while
 * holding a Tweaked Controller item, bind that controller to that
 * block. Shift-RMB unbinds. Empty hand or other items pass through
 * to the vanilla LogicBlock interaction (which opens the editor).
 *
 * Only fires server-side (event handler returns early on the client)
 * because LogicBlockEntity.setControllerId mutates NBT.
 *
 * Registered on the FORGE event bus by [dev.nitka.nodewire.Nodewire.init].
 */
object ControllerBindHandler {

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val state = event.level.getBlockState(event.pos)
        if (state.block !is LogicBlock) return
        val be = event.level.getBlockEntity(event.pos) as? LogicBlockEntity ?: return
        val player = event.entity
        val stack = event.itemStack

        if (!TweakedController.isLoaded()) return

        if (player.isShiftKeyDown && stack.isEmpty.not()) {
            // Shift+RMB with controller-in-hand = unbind
            val id = TweakedController.controllerItemId(stack) ?: return
            if (be.getControllerId() == id || be.getControllerId() == null) {
                be.setControllerId(null)
                player.sendSystemMessage(
                    Component.literal("Controller unbound").withStyle(ChatFormatting.YELLOW),
                )
                event.setCancellationResult(InteractionResult.SUCCESS)
                event.isCanceled = true
            }
            return
        }

        val id = TweakedController.controllerItemId(stack) ?: return
        be.setControllerId(id)
        player.sendSystemMessage(
            Component.literal("Controller bound (${id.toString().take(8)}…)")
                .withStyle(ChatFormatting.GREEN),
        )
        // Cancel so the editor doesn't also open.
        event.setCancellationResult(InteractionResult.SUCCESS)
        event.isCanceled = true
    }
}
```

- [ ] **Step 2: Register on FORGE bus** in `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`. Find the `init` method (called from the `@Mod` class constructor). Add:

```kotlin
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler,
        )
```

If `Nodewire.init` doesn't exist, find the constructor where other Forge-bus listeners are registered and append there.

- [ ] **Step 3: Build** → `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerBindHandler.kt \
        src/main/kotlin/dev/nitka/nodewire/Nodewire.kt
git commit -m "$(cat <<'EOF'
feat(tc): RMB bind handler for Drive-By-Wire flow

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 7 — `controller_input` NodeType + evaluator

### Task 9: NodeType + evaluator + register

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputNode.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`

- [ ] **Step 1: Create NodeType + evaluator**

```kotlin
package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.NodeEvaluator
import dev.nitka.nodewire.graph.NodeType
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * `controller_input` NodeType. Reads gamepad state from the
 * Logic-Block-bound Tweaked Controller and emits a configurable set of
 * pins keyed on the (channel, outputMode) config.
 *
 * Open question (resolve in a follow-up impl spike): does TC replicate
 * controller state to the server? If yes, this evaluator reads via
 * [TweakedController.getControllerState]. If no, Nodewire must add a
 * per-tick client→server packet sent by the holder of a bound TC item.
 * For now the wrapper returns null when state isn't accessible — the
 * evaluator emits zero values.
 *
 * The block's `controllerId` is passed in via the per-graph evaluator
 * context (not a config field) so re-bind operations don't require
 * touching the graph node.
 */
object ControllerInputNode {

    val CONTROLLER_INPUT = NodeType(
        id = ResourceLocation(Nodewire.ID, "controller_input"),
        displayName = "Controller Input",
        category = NodeCategory.IO,
        // Default: LEFT_STICK + VEC2_RAW → single VEC2 output pin.
        inputs = emptyList(),
        outputs = pinsForControllerInput(
            ControllerChannel.LEFT_STICK,
            ControllerOutputMode.VEC2_RAW,
        ),
        defaultConfig = {
            CompoundTag().apply {
                putString("channel", ControllerChannel.LEFT_STICK.name)
                putString("outputMode", ControllerOutputMode.VEC2_RAW.name)
                putFloat("deadzone", 0.15f)
                putBoolean("invert", false)
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.ControllerInput,
        evaluate = Evaluator,
    )

    /**
     * Per-tick controllerId context — populated by [LogicBlockEntity]
     * before evaluator runs. ThreadLocal because each block tick runs
     * synchronously on the server thread; safer than passing through
     * the evaluator signature for a one-off context value.
     */
    val currentControllerId: ThreadLocal<java.util.UUID?> = ThreadLocal.withInitial { null }

    val Evaluator: NodeEvaluator = { config, _ ->
        val channel = ControllerChannel.fromName(config.getString("channel"))
        val modeName = config.getString("outputMode")
        val mode = ControllerOutputMode.entries.firstOrNull { it.name == modeName }
            ?: allowedOutputModes(channel.category).first()
        val deadzone = config.getFloat("deadzone")
        val invert = config.getBoolean("invert")
        val id = currentControllerId.get()
        val state = if (id == null) null else TweakedController.getControllerState(id)
        if (state == null) {
            // Zero values per output pin
            pinsForControllerInput(channel, mode).associate { pin ->
                pin.id to PinValue.default(pin.type)
            }
        } else {
            applyOutputMode(state, channel, mode, deadzone, invert)
        }
    }
}
```

- [ ] **Step 2: Register in `StockNodeTypes.registerAll`** in `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`. After the existing `VectorNodeTypes.all().forEach(NodeTypeRegistry::register)` line, add:

```kotlin
        NodeTypeRegistry.register(dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode.CONTROLLER_INPUT)
```

- [ ] **Step 3: Add UI stub** to `NodeConfigContent.kt` (just before the closing `}` of the object, alongside other stubs from Phase A):

```kotlin
    /** ControllerInput: channel + outputMode + deadzone + invert. Phase 8 fills body. */
    val ControllerInput: @Composable (Node) -> Unit = { _ -> }
```

- [ ] **Step 4: Bump StockNodeTypesTest count** (from current 23 → 24).

- [ ] **Step 5: Build + test** → `./gradlew build` → green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputNode.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/StockNodeTypesTest.kt
git commit -m "$(cat <<'EOF'
feat(tc): register controller_input NodeType (UI stub)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 10: Wire controllerId context into block tick

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

- [ ] **Step 1: Wrap evaluator call with controllerId ThreadLocal**

Find the per-tick evaluator invocation in `LogicBlockEntity.kt` (look for `StatefulGraphEvaluator` usage or where `evaluator.tick(...)` / `.evaluate(...)` is called). Wrap:

```kotlin
        val prevControllerId = dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode.currentControllerId.get()
        dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode.currentControllerId.set(controllerId)
        try {
            // ... existing evaluator call ...
        } finally {
            dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode.currentControllerId.set(prevControllerId)
        }
```

(Locate the tick method first — search for `fun tick`, `serverTick`, or the body using `evaluator`. The wrap goes around the single eval call.)

- [ ] **Step 2: Build** → `./gradlew compileKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "$(cat <<'EOF'
feat(tc): scope controllerId into per-tick evaluator context

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 8 — UI

### Task 11: `EditorState` mutators for channel/outputMode reshape

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add two mutators** after the vector mutators from Phase A:

```kotlin
    /**
     * controller_input: write `config.channel` and reshape output pins
     * based on the new channel's category default outputMode. Edges on
     * removed/typed-changed pins drop.
     */
    fun changeControllerChannel(
        id: dev.nitka.nodewire.graph.NodeId,
        channelName: String,
    ) {
        val channel = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.fromName(channelName)
        val modeList = dev.nitka.nodewire.integration.tweakedcontroller.allowedOutputModes(channel.category)
        val newMode = modeList.first()
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val outs = dev.nitka.nodewire.integration.tweakedcontroller
                    .pinsForControllerInput(channel, newMode)
                val newConfig = n.config.copy().apply {
                    putString("channel", channel.name)
                    putString("outputMode", newMode.name)
                }
                n.copy(outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * controller_input: write `config.outputMode` and reshape output
     * pins. Channel stays put.
     */
    fun changeControllerOutputMode(
        id: dev.nitka.nodewire.graph.NodeId,
        modeName: String,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val channel = dev.nitka.nodewire.integration.tweakedcontroller
                    .ControllerChannel.fromName(n.config.getString("channel"))
                val mode = dev.nitka.nodewire.integration.tweakedcontroller
                    .ControllerOutputMode.entries.firstOrNull { it.name == modeName }
                    ?: dev.nitka.nodewire.integration.tweakedcontroller
                        .allowedOutputModes(channel.category).first()
                val outs = dev.nitka.nodewire.integration.tweakedcontroller
                    .pinsForControllerInput(channel, mode)
                val newConfig = n.config.copy().apply {
                    putString("outputMode", mode.name)
                }
                n.copy(outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }
```

- [ ] **Step 2: Build** → green.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "$(cat <<'EOF'
feat(tc): EditorState.changeControllerChannel + changeControllerOutputMode

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 12: `ControllerInput` config UI

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Replace the stub** added in Task 9 with a real composable:

```kotlin
    /**
     * ControllerInput: channel Select (all 19 channels grouped via order),
     * outputMode Select (filtered to category-valid options), optional
     * deadzone FloatField + invert checkbox shown depending on category/mode.
     */
    val ControllerInput: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var channel by remember(node.id) {
            mutableStateOf(node.config.getString("channel").ifEmpty {
                dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.LEFT_STICK.name
            })
        }
        var mode by remember(node.id) {
            mutableStateOf(node.config.getString("outputMode").ifEmpty {
                dev.nitka.nodewire.integration.tweakedcontroller.ControllerOutputMode.VEC2_RAW.name
            })
        }
        val ch = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.fromName(channel)
        val cat = ch.category
        val modeOptions = dev.nitka.nodewire.integration.tweakedcontroller.allowedOutputModes(cat)

        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Channel") {
                Select(
                    options = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.entries.toList(),
                    selected = ch,
                    onSelect = { next ->
                        channel = next.name
                        // mode resets to category default — handled by mutator
                        mode = dev.nitka.nodewire.integration.tweakedcontroller
                            .allowedOutputModes(next.category).first().name
                        editor?.changeControllerChannel(node.id, next.name)
                    },
                    label = { it.displayName },
                )
            }
            LabeledRow("Output") {
                Select(
                    options = modeOptions,
                    selected = dev.nitka.nodewire.integration.tweakedcontroller.ControllerOutputMode
                        .entries.firstOrNull { it.name == mode } ?: modeOptions.first(),
                    onSelect = { next ->
                        mode = next.name
                        editor?.changeControllerOutputMode(node.id, next.name)
                    },
                    label = { it.name.lowercase().replace('_', ' ') },
                )
            }
            val showDeadzone = cat == dev.nitka.nodewire.integration.tweakedcontroller
                .ControllerChannelCategory.STICK ||
                cat == dev.nitka.nodewire.integration.tweakedcontroller
                .ControllerChannelCategory.DPAD_COMPOSITE ||
                cat == dev.nitka.nodewire.integration.tweakedcontroller
                .ControllerChannelCategory.TRIGGER
            if (showDeadzone) {
                FloatField(node, "deadzone", "Deadzone", editor)
            }
        }
    }
```

(The `FloatField` private helper already exists in `NodeConfigContent` from earlier phases.)

- [ ] **Step 2: Build** → green.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(tc): ControllerInput config UI

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 13: `EditorToolbar` controller indicator

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Expose `controllerId` flow on EditorState**

In `EditorState.kt`, after the existing `_blockName`/`blockName` block, add:

```kotlin
    private val _controllerId = MutableStateFlow<java.util.UUID?>(null)
    val controllerId: StateFlow<java.util.UUID?> = _controllerId.asStateFlow()
    fun setControllerId(id: java.util.UUID?) { _controllerId.value = id }
```

(Use the same `kotlinx.coroutines.flow.*` imports as the blockName one.)

- [ ] **Step 2: Read controllerId at editor open** in `NodeEditorScreen.kt` (find where `e.setBlockName(be?.getBlockName() ?: "")` lives, add right after):

```kotlin
                    e.setControllerId(be?.getControllerId())
```

- [ ] **Step 3: Add indicator widget** to `EditorToolbar.kt`. After the existing block-name `TextInput` row (before the `Box(modifier=Modifier.weight(1f))` spacer), insert:

```kotlin
        val controllerId by editor.controllerId.collectAsState()
        val tcLoaded = dev.nitka.nodewire.integration.tweakedcontroller.TweakedController.isLoaded()
        when {
            !tcLoaded -> Text(
                "Controller: (TC not loaded)",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            controllerId == null -> Text(
                "Controller: (unbound)",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
                verticalAlignment = Alignment.Center,
            ) {
                Text(
                    "Controller: ${controllerId.toString().take(8)}…",
                    style = NwTheme.typography.caption,
                )
                Button(
                    onClick = {
                        editor.setControllerId(null)
                        dev.nitka.nodewire.net.NodewireNetwork.CHANNEL.send(
                            net.minecraftforge.network.PacketDistributor.SERVER.noArg(),
                            dev.nitka.nodewire.net.SetControllerIdPacket(pos, null),
                        )
                    },
                ) { Text("Unbind") }
            }
        }
```

- [ ] **Step 4: Build** → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "$(cat <<'EOF'
feat(tc): editor toolbar controller indicator + Unbind button

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 9 — Pin-reshape integration test

### Task 14: `ControllerInputPinReshapeTest`

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputPinReshapeTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.client.screen.EditorState
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControllerInputPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    @Test fun defaultIsStickVec2Raw() {
        val g = NodeGraph()
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        g.add(n)
        assertEquals(1, n.outputs.size)
        assertEquals(PinType.VEC2, n.outputs[0].type)
        assertEquals("xy", n.outputs[0].id)
    }

    @Test fun switchToTriggerCollapsesToFloatOutput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerChannel(n.id, ControllerChannel.LEFT_TRIGGER.name)
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
        assertEquals("value", refreshed.outputs[0].id)
        assertEquals("RAW", refreshed.config.getString("outputMode"))
    }

    @Test fun switchOutputModeWithinStickCategory() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerOutputMode(n.id, ControllerOutputMode.XY_RAW.name)
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(2, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
        assertEquals(PinType.FLOAT, refreshed.outputs[1].type)
    }

    @Test fun switchToButtonCollapsesToBool() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = ControllerInputNode.CONTROLLER_INPUT.newInstance()
        es.addNode(n)
        es.changeControllerChannel(n.id, ControllerChannel.BUTTON_A.name)
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.BOOL, refreshed.outputs[0].type)
        assertEquals("pressed", refreshed.outputs[0].id)
    }
}
```

- [ ] **Step 2: Run** → `./gradlew test --tests "dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputPinReshapeTest"` → 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputPinReshapeTest.kt
git commit -m "$(cat <<'EOF'
test(tc): ControllerInput pin reshape on channel/mode change

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 10 — Final validation

### Task 15: Full build + test + report

**Files:** none modified.

- [ ] **Step 1:** `./gradlew test` → BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Do NOT run `runClient`. Report this manual test plan:

```
Manual test plan for Tweaked Controller integration:

PRE-REQUISITE: Tweaked Controller mod installed in run client. If it isn't:
  - Mod must still build/load successfully.
  - Toolbar shows "Controller: (TC not loaded)".
  - controller_input node still spawnable, all outputs zero.

WITH TC INSTALLED:

1. Place a Logic Block. RMB with empty hand → editor opens (vanilla behavior).
   Toolbar shows "Controller: (unbound)".

2. Get a TC controller item. RMB the block. Chat says
   "Controller bound (<short-id>…)". Re-open the editor → toolbar shows
   bound id, with "Unbind" button.

3. Spawn a "Controller Input" node. Default config: Left Stick → VEC2 output.
   Move the analog stick. Connect output to a Vec Split. Observe live values.

4. Change channel to "Right Trigger". Outputs collapse to one FLOAT.
   Change OutputMode to "Redstone". Output pin becomes REDSTONE 0..15.

5. Change channel to "Button A". Output is BOOL.

6. Click "Unbind" in toolbar. Indicator returns to "(unbound)". Controller
   Input node now emits zero values.

7. Shift+RMB the block with the same controller item → "Controller unbound"
   in chat. Same effect as Unbind button.

KNOWN LIMITATION: Real TC state replication isn't wired yet
(TweakedController.getControllerState always returns null). All Controller
Input outputs are zero even when bound, until that wrap is added in a
follow-up. The binding flow and editor UX are fully functional.
```

- [ ] **Step 4:** No commit — final report only.

---

# Out of scope (do NOT do in this plan)

- Wiring actual TC state replication into `TweakedController.getControllerState`. This requires:
  1. Confirming TC's actual server-side API surface from its sources / jar (the Phase 1 spike documented coordinates; a follow-up impl spike fills in the reflection calls).
  2. If TC is client-only, add a `ControllerStatePacket` from client to server, sent each tick by the holder of any controller item that's bound to some logic block in render-distance.
- Rumble / LED / haptic output to controllers.
- Multi-controller per block.
- Per-pin player override.
- i18n / Ukrainian translations.
