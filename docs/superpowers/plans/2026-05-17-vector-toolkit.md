# Vector Toolkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add vector compose/decompose nodes (`vec_make`, `vec_split`), a universal polymorphic vector-math node (`vec_op` with 22 operations), and extend the `CONSTANT` node to support `VEC2` slot — so users can build vector pipelines in the node editor.

**Architecture:** Three new `NodeType`s registered in `StockNodeTypes`, evaluators in a new `VectorEvaluators` object (mirrors `StockEvaluators`), config UI in `NodeConfigContent`, pin-reshape mutators in `EditorState`. New `NodeCategory.VECTOR` with a dedicated header color. `PinType.VEC2`/`VEC3` and `PinValue.Vec2`/`Vec3` already exist — nothing to add at the type layer.

**Tech Stack:** Kotlin 2.0.20, Minecraft Forge 1.20.1, ModDevGradle legacyForge 2.0.141, Compose runtime 1.7.0, JUnit 5, existing Nodewire codec/registry infrastructure.

---

## File structure

**New files:**
- `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt` — three evaluators (`VecMake`, `VecSplit`, `VecOp`) + `VecOp` enum + helpers (`vec2In`, `vec3In`).
- `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt` — `VEC_MAKE`, `VEC_SPLIT`, `VEC_OP` `NodeType` definitions; called from `StockNodeTypes.registerAll`.
- `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt` — table-driven tests for every op on Vec2 + Vec3.
- `src/test/kotlin/dev/nitka/nodewire/graph/VecOpPinReshapeTest.kt` — `EditorState.changeVecOp` / `changeVecDim` reshape inputs/outputs + drop incompatible edges.

**Modified files:**
- `src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt` — add `VECTOR` to `NodeCategory`.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — register new types, extend `CONSTANT` default config with `x2/y2` keys, list new types in `registerAll`.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` — extend `Constant` evaluator to handle `VEC2`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/HeaderRenderer.kt` — add `VECTOR` header color.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — `VecMake`, `VecSplit`, `VecOp` composables; add `ConstantBodyVec2`; extend `CONSTANT_TYPES` list.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — `changeVecOp`, `changeVecDim`, `changeVecMakeSplitDim` mutators.

---

## Stay on master, no run client

- All commits go to **master**. No feature branch.
- Do **NOT** run `./gradlew runClient` — the user runs it manually and reports.
- Use `./gradlew build` and `./gradlew test` only.

---

# Phase 1 — Foundation

### Task 1: Add `NodeCategory.VECTOR` and header color

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt:12-19`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/HeaderRenderer.kt:14-21`

- [ ] **Step 1: Add VECTOR entry to NodeCategory**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt` — add one line:

```kotlin
enum class NodeCategory(val displayName: String) {
    IO("I/O"),
    LOGIC("Logic"),
    MATH("Math"),
    VECTOR("Vector"),
    CONVERSION("Conversion"),
    FLOW("Flow"),
    CONSTANTS("Constants"),
}
```

- [ ] **Step 2: Add VECTOR header color**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/HeaderRenderer.kt:14-21` — add `VECTOR` branch in `when`:

```kotlin
fun headerColorFor(category: NodeCategory): Color = when (category) {
    NodeCategory.IO         -> Color(0xFF_2E_5A_A8.toInt())  // steel blue
    NodeCategory.LOGIC      -> Color(0xFF_A0_38_38.toInt())  // crimson
    NodeCategory.MATH       -> Color(0xFF_7E_6A_2A.toInt())  // ochre
    NodeCategory.VECTOR     -> Color(0xFF_8C_5A_E8.toInt())  // accent purple
    NodeCategory.CONVERSION -> Color(0xFF_A8_5A_2E.toInt())  // burnt orange
    NodeCategory.FLOW       -> Color(0xFF_6A_38_A0.toInt())  // violet
    NodeCategory.CONSTANTS  -> Color(0xFF_38_82_4A.toInt())  // forest green
}
```

- [ ] **Step 3: Build to verify no compile errors**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/HeaderRenderer.kt
git commit -m "$(cat <<'EOF'
feat(vector): NodeCategory.VECTOR + accent-purple header color

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — `vec_make` (compose)

### Task 2: Skeleton `VectorEvaluators` with helpers

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`

- [ ] **Step 1: Create file with input helpers (no evaluators yet)**

Write `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`:

```kotlin
package dev.nitka.nodewire.graph

/**
 * Evaluators for the vector node types. Pure functions; mirror
 * [StockEvaluators] style. Helpers convert unbound or wrong-typed pins
 * to zero-vectors so the graph never sees NaN / nulls.
 */
object VectorEvaluators {

    // helpers --------------------------------------------------------

    internal fun vec2In(inputs: Map<String, PinValue>, pin: String): PinValue.Vec2 =
        (inputs[pin] as? PinValue.Vec2) ?: PinValue.Vec2(0f, 0f)

    internal fun vec3In(inputs: Map<String, PinValue>, pin: String): PinValue.Vec3 =
        (inputs[pin] as? PinValue.Vec3) ?: PinValue.Vec3(0f, 0f, 0f)

    internal fun floatIn(inputs: Map<String, PinValue>, pin: String): Float =
        (inputs[pin] as? PinValue.Float)?.value ?: 0f
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt
git commit -m "$(cat <<'EOF'
feat(vector): VectorEvaluators skeleton with pin helpers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3: `VecMake` evaluator + tests

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Write failing tests for VecMake**

Create `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`:

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorEvaluatorsTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun vecMakeVec2() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC2") },
            mapOf("x" to PinValue.Float(3f), "y" to PinValue.Float(4f)),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecMakeVec3() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC3") },
            mapOf(
                "x" to PinValue.Float(1f),
                "y" to PinValue.Float(2f),
                "z" to PinValue.Float(3f),
            ),
        )
        assertEquals(PinValue.Vec3(1f, 2f, 3f), out["out"])
    }

    @Test fun vecMakeUnknownDimFallsBackToVec2() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "BOGUS") },
            mapOf("x" to PinValue.Float(5f), "y" to PinValue.Float(6f)),
        )
        assertEquals(PinValue.Vec2(5f, 6f), out["out"])
    }

    @Test fun vecMakeMissingInputsZero() {
        val out = VectorEvaluators.VecMake(
            cfg { putString("dim", "VEC3") },
            emptyMap(),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 0f), out["out"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: FAIL with `Unresolved reference: VecMake`.

- [ ] **Step 3: Implement VecMake**

Append to `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt` (after the helpers block, before the closing `}`):

```kotlin
    // --- compose -----------------------------------------------------

    /**
     * VecMake: outputs Vec2 or Vec3 driven by `config.dim`. Missing
     * scalar inputs default to 0f. Unknown dim falls back to VEC2.
     */
    val VecMake: NodeEvaluator = { config, inputs ->
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        val out: PinValue = when (dim) {
            "VEC3" -> PinValue.Vec3(
                floatIn(inputs, "x"),
                floatIn(inputs, "y"),
                floatIn(inputs, "z"),
            )
            else -> PinValue.Vec2(
                floatIn(inputs, "x"),
                floatIn(inputs, "y"),
            )
        }
        mapOf("out" to out)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecMake evaluator + tests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: Register `vec_make` NodeType

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`

- [ ] **Step 1: Create VectorNodeTypes file**

Write `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt`:

```kotlin
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
```

- [ ] **Step 2: Hook into StockNodeTypes.registerAll**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — modify `registerAll`:

```kotlin
    /** Registers every stock type into [NodeTypeRegistry]. Idempotent. */
    fun registerAll() {
        listOf(
            // IO
            SIDE_INPUT, SIDE_OUTPUT, CHANNEL_INPUT, CHANNEL_OUTPUT,
            REDSTONE_LINK_INPUT, REDSTONE_LINK_OUTPUT,
            // Logic
            LOGIC_GATE,
            // Constants
            CONSTANT, TIMER,
            // Math
            MATH, COMPARE,
            // Conversion
            CONVERT,
            // Flow
            SELECT_BOOL, EDGE_RISING, TOGGLE, COUNTER, DELAY,
            // Test / Generators
            RANDOM_BOOL, RANDOM_INT, PULSE,
        ).forEach(NodeTypeRegistry::register)
        VectorNodeTypes.all().forEach(NodeTypeRegistry::register)
    }
```

- [ ] **Step 3: Add stub for `NodeConfigContent.VecMake` so compilation succeeds**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — add at the bottom of the `NodeConfigContent` object (before the closing `}`):

```kotlin
    /**
     * VecMake: dim selector. Stub for Phase 6 — currently no-op UI so
     * the NodeType registration compiles.
     */
    val VecMake: @Composable (Node) -> Unit = { _ -> }
```

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(vector): register vec_make NodeType (UI stub)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — `vec_split` (decompose)

### Task 5: `VecSplit` evaluator + tests

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Add failing tests for VecSplit**

Append to `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt` (before the final `}`):

```kotlin
    @Test fun vecSplitVec2() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC2") },
            mapOf("in" to PinValue.Vec2(7f, 8f)),
        )
        assertEquals(PinValue.Float(7f), out["x"])
        assertEquals(PinValue.Float(8f), out["y"])
    }

    @Test fun vecSplitVec3() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC3") },
            mapOf("in" to PinValue.Vec3(1f, 2f, 3f)),
        )
        assertEquals(PinValue.Float(1f), out["x"])
        assertEquals(PinValue.Float(2f), out["y"])
        assertEquals(PinValue.Float(3f), out["z"])
    }

    @Test fun vecSplitMissingInputZero() {
        val out = VectorEvaluators.VecSplit(
            cfg { putString("dim", "VEC3") },
            emptyMap(),
        )
        assertEquals(PinValue.Float(0f), out["x"])
        assertEquals(PinValue.Float(0f), out["y"])
        assertEquals(PinValue.Float(0f), out["z"])
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: FAIL with `Unresolved reference: VecSplit`.

- [ ] **Step 3: Implement VecSplit**

Append to `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt` (right after `VecMake`, before the closing `}`):

```kotlin
    // --- decompose ---------------------------------------------------

    /**
     * VecSplit: outputs x/y (Vec2) or x/y/z (Vec3) scalars from a single
     * vector input named "in". Missing input → all zeros.
     */
    val VecSplit: NodeEvaluator = { config, inputs ->
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        if (dim == "VEC3") {
            val v = vec3In(inputs, "in")
            mapOf(
                "x" to PinValue.Float(v.x),
                "y" to PinValue.Float(v.y),
                "z" to PinValue.Float(v.z),
            )
        } else {
            val v = vec2In(inputs, "in")
            mapOf(
                "x" to PinValue.Float(v.x),
                "y" to PinValue.Float(v.y),
            )
        }
    }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecSplit evaluator + tests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6: Register `vec_split` NodeType

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Add VEC_SPLIT entry**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt` — replace contents (keep `VEC_MAKE` block, add `VEC_SPLIT`, update `all()`):

```kotlin
    val VEC_SPLIT = NodeType(
        id = ResourceLocation(Nodewire.ID, "vec_split"),
        displayName = "Vec Split",
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

    fun all(): List<NodeType> = listOf(VEC_MAKE, VEC_SPLIT)
```

- [ ] **Step 2: Add VecSplit UI stub**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — add after the `VecMake` stub:

```kotlin
    /** VecSplit: dim selector. Phase 6 fills body. */
    val VecSplit: @Composable (Node) -> Unit = { _ -> }
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(vector): register vec_split NodeType (UI stub)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — `vec_op` core (binary vec→vec ops)

### Task 7: `VecOp` enum + dispatch skeleton + ADD/SUB

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Add failing tests for ADD and SUB**

Append to `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`:

```kotlin
    private fun opCfg(op: String, dim: String): CompoundTag = cfg {
        putString("op", op); putString("dim", dim)
    }

    // VecOp.ADD ------------------------------------------------------

    @Test fun vecOpAddVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("ADD", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(1f, 2f),
                "b" to PinValue.Vec2(3f, 4f),
            ),
        )
        assertEquals(PinValue.Vec2(4f, 6f), out["out"])
    }

    @Test fun vecOpAddVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("ADD", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(10f, 20f, 30f),
            ),
        )
        assertEquals(PinValue.Vec3(11f, 22f, 33f), out["out"])
    }

    // VecOp.SUB ------------------------------------------------------

    @Test fun vecOpSubVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("SUB", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(5f, 7f),
                "b" to PinValue.Vec2(2f, 3f),
            ),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecOpSubVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("SUB", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(10f, 20f, 30f),
                "b" to PinValue.Vec3(1f, 2f, 3f),
            ),
        )
        assertEquals(PinValue.Vec3(9f, 18f, 27f), out["out"])
    }

    @Test fun vecOpUnknownOpReturnsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("BOGUS", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 1f), "b" to PinValue.Vec2(1f, 1f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: FAIL with `Unresolved reference: VecOp`.

- [ ] **Step 3: Implement VecOp enum + dispatch (ADD, SUB only for now)**

Append to `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt` (before the final `}`):

```kotlin
    // --- ops ---------------------------------------------------------

    /**
     * All operations exposed by [VecOp]. Names match exactly what's
     * written to/read from `config.op`. Unknown name → ADD-like fallback
     * with all-zero output (see [VecOp]).
     */
    enum class VecOp {
        ADD, SUB, MUL_COMPONENT, MIN, MAX,
        NEGATE, NORMALIZE, ABS,
        SCALE, CLAMP_MAG, LERP, PROJECT, REFLECT,
        DOT, LENGTH, LENGTH_SQ, DISTANCE, ANGLE,
        CROSS, ROTATE2D, TO_VEC3, TO_VEC2;

        companion object {
            fun fromName(name: String): VecOp =
                entries.firstOrNull { it.name == name } ?: ADD
        }
    }

    /**
     * VecOp: universal vector math node. Dispatches on `config.op` (a
     * [VecOp] name) and `config.dim` (VEC2/VEC3). Some ops force a fixed
     * dim (CROSS=VEC3, ROTATE2D=VEC2, TO_VEC2/TO_VEC3 ignore dim) —
     * that's handled by the editor's pin-reshape mutator; the evaluator
     * still respects `dim` for the configurable ops.
     *
     * Zero-vector edge cases:
     *   * NORMALIZE(0)  -> 0
     *   * LENGTH(0)     -> 0
     *   * ANGLE(0, _)   -> 0
     *   * PROJECT(_, 0) -> 0
     *   * Division-by-zero never happens (no scalar / vector by vector).
     */
    val VecOp: NodeEvaluator = { config, inputs ->
        val op = VecOp.fromName(config.getString("op").ifEmpty { "ADD" })
        val dim = config.getString("dim").ifEmpty { "VEC2" }
        val v2 = dim == "VEC2"
        val out: PinValue = when (op) {
            VecOp.ADD -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x + b.x, a.y + b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x + b.x, a.y + b.y, a.z + b.z)
            }
            VecOp.SUB -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x - b.x, a.y - b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
            }
            else -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
        }
        mapOf("out" to out)
    }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp enum + ADD/SUB dispatch

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 8: VecOp MUL_COMPONENT, MIN, MAX

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

Append to `VectorEvaluatorsTest.kt`:

```kotlin
    @Test fun vecOpMulComponentVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("MUL_COMPONENT", "VEC2"),
            mapOf("a" to PinValue.Vec2(2f, 3f), "b" to PinValue.Vec2(4f, 5f)),
        )
        assertEquals(PinValue.Vec2(8f, 15f), out["out"])
    }

    @Test fun vecOpMulComponentVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("MUL_COMPONENT", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(4f, 5f, 6f),
            ),
        )
        assertEquals(PinValue.Vec3(4f, 10f, 18f), out["out"])
    }

    @Test fun vecOpMinVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("MIN", "VEC2"),
            mapOf("a" to PinValue.Vec2(5f, 1f), "b" to PinValue.Vec2(2f, 7f)),
        )
        assertEquals(PinValue.Vec2(2f, 1f), out["out"])
    }

    @Test fun vecOpMaxVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("MAX", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 5f, 3f),
                "b" to PinValue.Vec3(4f, 2f, 9f),
            ),
        )
        assertEquals(PinValue.Vec3(4f, 5f, 9f), out["out"])
    }
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 4 tests FAIL (output is zero from `else` branch).

- [ ] **Step 3: Add branches in VecOp**

In `VectorEvaluators.kt`, replace the `else -> if (v2) ... else ...` branch at the bottom of the `when (op)` block with these new cases (keep the catch-all `else` at the very end):

```kotlin
            VecOp.MUL_COMPONENT -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(a.x * b.x, a.y * b.y)
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(a.x * b.x, a.y * b.y, a.z * b.z)
            }
            VecOp.MIN -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(minOf(a.x, b.x), minOf(a.y, b.y))
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            }
            VecOp.MAX -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                PinValue.Vec2(maxOf(a.x, b.x), maxOf(a.y, b.y))
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
            }
            else -> if (v2) PinValue.Vec2(0f, 0f) else PinValue.Vec3(0f, 0f, 0f)
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 16 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp MUL_COMPONENT, MIN, MAX

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 9: VecOp NEGATE, NORMALIZE, ABS (unary)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

Append to `VectorEvaluatorsTest.kt`:

```kotlin
    @Test fun vecOpNegateVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("NEGATE", "VEC2"),
            mapOf("v" to PinValue.Vec2(2f, -3f)),
        )
        assertEquals(PinValue.Vec2(-2f, 3f), out["out"])
    }

    @Test fun vecOpNegateVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("NEGATE", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, -2f, 3f)),
        )
        assertEquals(PinValue.Vec3(-1f, 2f, -3f), out["out"])
    }

    @Test fun vecOpAbsVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("ABS", "VEC2"),
            mapOf("v" to PinValue.Vec2(-2f, 3f)),
        )
        assertEquals(PinValue.Vec2(2f, 3f), out["out"])
    }

    @Test fun vecOpNormalizeVec2Unit() {
        val out = VectorEvaluators.VecOp(
            opCfg("NORMALIZE", "VEC2"),
            mapOf("v" to PinValue.Vec2(3f, 4f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(0.6f, v.x, 0.0001f)
        assertEquals(0.8f, v.y, 0.0001f)
    }

    @Test fun vecOpNormalizeZeroStaysZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("NORMALIZE", "VEC2"),
            mapOf("v" to PinValue.Vec2(0f, 0f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }
```

Note: `assertEquals(Float, Float, delta)` is JUnit's float overload; the imports already include `assertEquals`.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 5 tests FAIL.

- [ ] **Step 3: Add unary cases**

In `VectorEvaluators.kt`, insert before the catch-all `else`:

```kotlin
            VecOp.NEGATE -> if (v2) {
                val a = vec2In(inputs, "v")
                PinValue.Vec2(-a.x, -a.y)
            } else {
                val a = vec3In(inputs, "v")
                PinValue.Vec3(-a.x, -a.y, -a.z)
            }
            VecOp.ABS -> if (v2) {
                val a = vec2In(inputs, "v")
                PinValue.Vec2(kotlin.math.abs(a.x), kotlin.math.abs(a.y))
            } else {
                val a = vec3In(inputs, "v")
                PinValue.Vec3(
                    kotlin.math.abs(a.x),
                    kotlin.math.abs(a.y),
                    kotlin.math.abs(a.z),
                )
            }
            VecOp.NORMALIZE -> if (v2) {
                val a = vec2In(inputs, "v")
                val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                if (len == 0f) PinValue.Vec2(0f, 0f)
                else PinValue.Vec2(a.x / len, a.y / len)
            } else {
                val a = vec3In(inputs, "v")
                val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                if (len == 0f) PinValue.Vec3(0f, 0f, 0f)
                else PinValue.Vec3(a.x / len, a.y / len, a.z / len)
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 21 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp NEGATE, NORMALIZE, ABS (unary)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 10: VecOp SCALE, CLAMP_MAG, LERP (scalar-mixed)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
    @Test fun vecOpScaleVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("SCALE", "VEC2"),
            mapOf("v" to PinValue.Vec2(2f, 3f), "s" to PinValue.Float(4f)),
        )
        assertEquals(PinValue.Vec2(8f, 12f), out["out"])
    }

    @Test fun vecOpScaleVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("SCALE", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, 2f, 3f), "s" to PinValue.Float(-2f)),
        )
        assertEquals(PinValue.Vec3(-2f, -4f, -6f), out["out"])
    }

    @Test fun vecOpClampMagBelow() {
        val out = VectorEvaluators.VecOp(
            opCfg("CLAMP_MAG", "VEC2"),
            // length = 5, max = 10 → unchanged
            mapOf("v" to PinValue.Vec2(3f, 4f), "max" to PinValue.Float(10f)),
        )
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vecOpClampMagAbove() {
        val out = VectorEvaluators.VecOp(
            opCfg("CLAMP_MAG", "VEC2"),
            // length = 5, max = 2 → scaled to length 2 (i.e. (1.2, 1.6))
            mapOf("v" to PinValue.Vec2(3f, 4f), "max" to PinValue.Float(2f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(1.2f, v.x, 0.0001f)
        assertEquals(1.6f, v.y, 0.0001f)
    }

    @Test fun vecOpLerpVec2Middle() {
        val out = VectorEvaluators.VecOp(
            opCfg("LERP", "VEC2"),
            mapOf(
                "a" to PinValue.Vec2(0f, 0f),
                "b" to PinValue.Vec2(10f, 20f),
                "t" to PinValue.Float(0.5f),
            ),
        )
        assertEquals(PinValue.Vec2(5f, 10f), out["out"])
    }

    @Test fun vecOpLerpVec3End() {
        val out = VectorEvaluators.VecOp(
            opCfg("LERP", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(7f, 8f, 9f),
                "t" to PinValue.Float(1f),
            ),
        )
        assertEquals(PinValue.Vec3(7f, 8f, 9f), out["out"])
    }
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 6 FAIL.

- [ ] **Step 3: Add cases**

Insert before the catch-all `else`:

```kotlin
            VecOp.SCALE -> {
                val s = floatIn(inputs, "s")
                if (v2) {
                    val a = vec2In(inputs, "v")
                    PinValue.Vec2(a.x * s, a.y * s)
                } else {
                    val a = vec3In(inputs, "v")
                    PinValue.Vec3(a.x * s, a.y * s, a.z * s)
                }
            }
            VecOp.CLAMP_MAG -> {
                val max = floatIn(inputs, "max")
                if (v2) {
                    val a = vec2In(inputs, "v")
                    val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                    if (len <= max || len == 0f) PinValue.Vec2(a.x, a.y)
                    else PinValue.Vec2(a.x / len * max, a.y / len * max)
                } else {
                    val a = vec3In(inputs, "v")
                    val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                    if (len <= max || len == 0f) PinValue.Vec3(a.x, a.y, a.z)
                    else PinValue.Vec3(
                        a.x / len * max, a.y / len * max, a.z / len * max,
                    )
                }
            }
            VecOp.LERP -> {
                val t = floatIn(inputs, "t")
                if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    PinValue.Vec2(
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                    )
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    PinValue.Vec3(
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                        a.z + (b.z - a.z) * t,
                    )
                }
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 27 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp SCALE, CLAMP_MAG, LERP

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 11: VecOp PROJECT, REFLECT

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
    @Test fun vecOpProjectVec2OnXAxis() {
        // project (3, 4) onto (1, 0) → (3, 0)
        val out = VectorEvaluators.VecOp(
            opCfg("PROJECT", "VEC2"),
            mapOf("a" to PinValue.Vec2(3f, 4f), "b" to PinValue.Vec2(1f, 0f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(3f, v.x, 0.0001f)
        assertEquals(0f, v.y, 0.0001f)
    }

    @Test fun vecOpProjectOnZeroIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("PROJECT", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 2f), "b" to PinValue.Vec2(0f, 0f)),
        )
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }

    @Test fun vecOpReflectVec2FlipsY() {
        // reflect (1, -1) across normal (0, 1) → (1, 1)
        val out = VectorEvaluators.VecOp(
            opCfg("REFLECT", "VEC2"),
            mapOf("v" to PinValue.Vec2(1f, -1f), "n" to PinValue.Vec2(0f, 1f)),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(1f, v.x, 0.0001f)
        assertEquals(1f, v.y, 0.0001f)
    }
```

- [ ] **Step 2: Verify failure** — 3 FAIL.

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`

- [ ] **Step 3: Add cases**

Insert before the catch-all `else`:

```kotlin
            VecOp.PROJECT -> if (v2) {
                val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                val bLenSq = b.x * b.x + b.y * b.y
                if (bLenSq == 0f) PinValue.Vec2(0f, 0f)
                else {
                    val k = (a.x * b.x + a.y * b.y) / bLenSq
                    PinValue.Vec2(b.x * k, b.y * k)
                }
            } else {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                val bLenSq = b.x * b.x + b.y * b.y + b.z * b.z
                if (bLenSq == 0f) PinValue.Vec3(0f, 0f, 0f)
                else {
                    val k = (a.x * b.x + a.y * b.y + a.z * b.z) / bLenSq
                    PinValue.Vec3(b.x * k, b.y * k, b.z * k)
                }
            }
            VecOp.REFLECT -> if (v2) {
                val vIn = vec2In(inputs, "v"); val n = vec2In(inputs, "n")
                // r = v − 2 (v·n) n
                val d = 2f * (vIn.x * n.x + vIn.y * n.y)
                PinValue.Vec2(vIn.x - d * n.x, vIn.y - d * n.y)
            } else {
                val vIn = vec3In(inputs, "v"); val n = vec3In(inputs, "n")
                val d = 2f * (vIn.x * n.x + vIn.y * n.y + vIn.z * n.z)
                PinValue.Vec3(
                    vIn.x - d * n.x,
                    vIn.y - d * n.y,
                    vIn.z - d * n.z,
                )
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 30 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp PROJECT, REFLECT

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 12: VecOp reductions — DOT, LENGTH, LENGTH_SQ, DISTANCE, ANGLE

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
    @Test fun vecOpDotVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("DOT", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 2f), "b" to PinValue.Vec2(3f, 4f)),
        )
        // 1*3 + 2*4 = 11
        assertEquals(PinValue.Float(11f), out["out"])
    }

    @Test fun vecOpDotVec3Orthogonal() {
        val out = VectorEvaluators.VecOp(
            opCfg("DOT", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 0f, 0f),
                "b" to PinValue.Vec3(0f, 1f, 0f),
            ),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }

    @Test fun vecOpLengthVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH", "VEC2"),
            mapOf("v" to PinValue.Vec2(3f, 4f)),
        )
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun vecOpLengthZeroIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH", "VEC3"),
            mapOf("v" to PinValue.Vec3(0f, 0f, 0f)),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }

    @Test fun vecOpLengthSqVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("LENGTH_SQ", "VEC3"),
            mapOf("v" to PinValue.Vec3(2f, 3f, 6f)),
        )
        // 4 + 9 + 36 = 49
        assertEquals(PinValue.Float(49f), out["out"])
    }

    @Test fun vecOpDistanceVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("DISTANCE", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 1f), "b" to PinValue.Vec2(4f, 5f)),
        )
        // sqrt(9 + 16) = 5
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun vecOpAngleVec2Orthogonal() {
        val out = VectorEvaluators.VecOp(
            opCfg("ANGLE", "VEC2"),
            mapOf("a" to PinValue.Vec2(1f, 0f), "b" to PinValue.Vec2(0f, 1f)),
        )
        val f = (out["out"] as PinValue.Float).value
        assertEquals((kotlin.math.PI / 2).toFloat(), f, 0.0001f)
    }

    @Test fun vecOpAngleWithZeroVectorIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("ANGLE", "VEC2"),
            mapOf("a" to PinValue.Vec2(0f, 0f), "b" to PinValue.Vec2(1f, 0f)),
        )
        assertEquals(PinValue.Float(0f), out["out"])
    }
```

- [ ] **Step 2: Verify failure** — 8 FAIL.

- [ ] **Step 3: Add cases** — these return `PinValue.Float`, NOT a vector. So the catch-all `else` (which always returns a vector) would mask a missing op; that's fine — but make sure to insert before catch-all:

```kotlin
            VecOp.DOT -> {
                val f = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    a.x * b.x + a.y * b.y
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    a.x * b.x + a.y * b.y + a.z * b.z
                }
                PinValue.Float(f)
            }
            VecOp.LENGTH -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "v")
                    a.x * a.x + a.y * a.y
                } else {
                    val a = vec3In(inputs, "v")
                    a.x * a.x + a.y * a.y + a.z * a.z
                }
                PinValue.Float(kotlin.math.sqrt(sq))
            }
            VecOp.LENGTH_SQ -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "v")
                    a.x * a.x + a.y * a.y
                } else {
                    val a = vec3In(inputs, "v")
                    a.x * a.x + a.y * a.y + a.z * a.z
                }
                PinValue.Float(sq)
            }
            VecOp.DISTANCE -> {
                val sq = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    val dx = a.x - b.x; val dy = a.y - b.y
                    dx * dx + dy * dy
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
                    dx * dx + dy * dy + dz * dz
                }
                PinValue.Float(kotlin.math.sqrt(sq))
            }
            VecOp.ANGLE -> {
                val theta = if (v2) {
                    val a = vec2In(inputs, "a"); val b = vec2In(inputs, "b")
                    val la = kotlin.math.sqrt(a.x * a.x + a.y * a.y)
                    val lb = kotlin.math.sqrt(b.x * b.x + b.y * b.y)
                    if (la == 0f || lb == 0f) 0f
                    else kotlin.math.acos(
                        ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f),
                    )
                } else {
                    val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                    val la = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                    val lb = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
                    if (la == 0f || lb == 0f) 0f
                    else kotlin.math.acos(
                        ((a.x * b.x + a.y * b.y + a.z * b.z) / (la * lb))
                            .coerceIn(-1f, 1f),
                    )
                }
                PinValue.Float(theta)
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 38 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp reductions (DOT, LENGTH, LENGTH_SQ, DISTANCE, ANGLE)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 13: VecOp CROSS, ROTATE2D, TO_VEC3, TO_VEC2 (dim-locked + conversions)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
    @Test fun vecOpCrossXY() {
        val out = VectorEvaluators.VecOp(
            opCfg("CROSS", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 0f, 0f),
                "b" to PinValue.Vec3(0f, 1f, 0f),
            ),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 1f), out["out"])
    }

    @Test fun vecOpCrossParallelIsZero() {
        val out = VectorEvaluators.VecOp(
            opCfg("CROSS", "VEC3"),
            mapOf(
                "a" to PinValue.Vec3(1f, 2f, 3f),
                "b" to PinValue.Vec3(2f, 4f, 6f),
            ),
        )
        assertEquals(PinValue.Vec3(0f, 0f, 0f), out["out"])
    }

    @Test fun vecOpRotate2dQuarterTurn() {
        // rotate (1, 0) by π/2 → (0, 1)
        val out = VectorEvaluators.VecOp(
            opCfg("ROTATE2D", "VEC2"),
            mapOf(
                "v" to PinValue.Vec2(1f, 0f),
                "angle" to PinValue.Float((kotlin.math.PI / 2).toFloat()),
            ),
        )
        val v = out["out"] as PinValue.Vec2
        assertEquals(0f, v.x, 0.0001f)
        assertEquals(1f, v.y, 0.0001f)
    }

    @Test fun vecOpToVec3() {
        val out = VectorEvaluators.VecOp(
            opCfg("TO_VEC3", "VEC2"),
            mapOf("v" to PinValue.Vec2(1f, 2f), "z" to PinValue.Float(3f)),
        )
        assertEquals(PinValue.Vec3(1f, 2f, 3f), out["out"])
    }

    @Test fun vecOpToVec2() {
        val out = VectorEvaluators.VecOp(
            opCfg("TO_VEC2", "VEC3"),
            mapOf("v" to PinValue.Vec3(1f, 2f, 3f)),
        )
        assertEquals(PinValue.Vec2(1f, 2f), out["out"])
    }
```

- [ ] **Step 2: Verify failure** — 5 FAIL.

- [ ] **Step 3: Add cases**

Insert before the catch-all `else`:

```kotlin
            VecOp.CROSS -> {
                val a = vec3In(inputs, "a"); val b = vec3In(inputs, "b")
                PinValue.Vec3(
                    a.y * b.z - a.z * b.y,
                    a.z * b.x - a.x * b.z,
                    a.x * b.y - a.y * b.x,
                )
            }
            VecOp.ROTATE2D -> {
                val a = vec2In(inputs, "v")
                val theta = floatIn(inputs, "angle")
                val c = kotlin.math.cos(theta); val s = kotlin.math.sin(theta)
                PinValue.Vec2(a.x * c - a.y * s, a.x * s + a.y * c)
            }
            VecOp.TO_VEC3 -> {
                val a = vec2In(inputs, "v")
                PinValue.Vec3(a.x, a.y, floatIn(inputs, "z"))
            }
            VecOp.TO_VEC2 -> {
                val a = vec3In(inputs, "v")
                PinValue.Vec2(a.x, a.y)
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VectorEvaluatorsTest"`
Expected: 43 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp CROSS, ROTATE2D, TO_VEC3, TO_VEC2

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 14: Register `vec_op` NodeType

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Add VEC_OP entry**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt` — add this `VEC_OP` block after `VEC_SPLIT` and update `all()`:

```kotlin
    val VEC_OP = NodeType(
        id = ResourceLocation(Nodewire.ID, "vec_op"),
        displayName = "Vec Op",
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
```

- [ ] **Step 2: Add VecOp UI stub**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — add after the `VecSplit` stub:

```kotlin
    /** VecOp: op + dim selectors. Phase 6 fills body. */
    val VecOp: @Composable (Node) -> Unit = { _ -> }
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/VectorNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(vector): register vec_op NodeType (UI stub)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 5 — CONSTANT VEC2 slot

### Task 15: Extend `Constant` evaluator to handle VEC2

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt:43-56`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt:129-146`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConstantVec2Test.kt`

- [ ] **Step 1: Write failing test for VEC2 constant**

Create `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConstantVec2Test.kt`:

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConstantVec2Test {

    @Test fun vec2ConstantReadsX2Y2() {
        val cfg = CompoundTag().apply {
            putString("type", "VEC2")
            putFloat("x2", 3f); putFloat("y2", 4f)
        }
        val out = StockEvaluators.Constant(cfg, emptyMap())
        assertEquals(PinValue.Vec2(3f, 4f), out["out"])
    }

    @Test fun vec2ConstantMissingFieldsDefaultToZero() {
        val cfg = CompoundTag().apply { putString("type", "VEC2") }
        val out = StockEvaluators.Constant(cfg, emptyMap())
        assertEquals(PinValue.Vec2(0f, 0f), out["out"])
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsConstantVec2Test"`
Expected: 2 FAIL (the current `else` branch returns `Bool(false)`).

- [ ] **Step 3: Extend Constant evaluator**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt:43-56` — replace the `Constant` evaluator block with:

```kotlin
    val Constant: NodeEvaluator = { config, _ ->
        val type = PinType.fromName(config.getString("type").ifEmpty { PinType.BOOL.name })
        val out: PinValue = when (type) {
            PinType.BOOL -> PinValue.Bool(config.getBoolean("bool"))
            PinType.INT -> PinValue.Int(config.getInt("int"))
            PinType.FLOAT -> PinValue.Float(config.getFloat("float"))
            PinType.STRING -> PinValue.Str(config.getString("string"))
            PinType.VEC2 -> PinValue.Vec2(
                config.getFloat("x2"), config.getFloat("y2"),
            )
            PinType.VEC3 -> PinValue.Vec3(
                config.getFloat("x"), config.getFloat("y"), config.getFloat("z"),
            )
            else -> PinValue.default(PinType.BOOL)
        }
        mapOf("out" to out)
    }
```

Note: `VEC2` uses keys `x2`/`y2` to avoid colliding with VEC3's `x/y/z`. The default-config block in Task 16 will seed both sets.

- [ ] **Step 4: Extend CONSTANT default config**

Edit `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt:134-143` — replace the `defaultConfig` block of `CONSTANT`:

```kotlin
        defaultConfig = {
            CompoundTag().apply {
                putString("type", PinType.BOOL.name)
                putBoolean("bool", false)
                putInt("int", 0)
                putFloat("float", 0f)
                putString("string", "")
                putFloat("x", 0f); putFloat("y", 0f); putFloat("z", 0f)
                putFloat("x2", 0f); putFloat("y2", 0f)
            }
        },
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsConstantVec2Test"`
Expected: 2 pass.

- [ ] **Step 6: Run full test suite to verify no regression**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConstantVec2Test.kt
git commit -m "$(cat <<'EOF'
feat(constant): VEC2 slot in Constant node

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 6 — UI

### Task 16: VecMake / VecSplit dim Select

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add `changeVecMakeSplitDim` mutator on EditorState**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — append after `changeConvertTypes` (around line 429), inside the EditorState class:

```kotlin
    /**
     * VecMake / VecSplit node: rebuild input/output pins for the new
     * dimension and write `config.dim`. VecMake has scalar inputs +
     * vector output; VecSplit is the inverse. Identify by node typeKey.
     */
    fun changeVecMakeSplitDim(
        id: dev.nitka.nodewire.graph.NodeId,
        newDim: String,  // "VEC2" or "VEC3"
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val isVec2 = newDim == "VEC2"
                val vecType = if (isVec2) dev.nitka.nodewire.graph.PinType.VEC2 else dev.nitka.nodewire.graph.PinType.VEC3
                val isMake = n.typeKey.path == "vec_make"
                val newInputs: List<dev.nitka.nodewire.graph.Pin>
                val newOutputs: List<dev.nitka.nodewire.graph.Pin>
                if (isMake) {
                    val xs = mutableListOf(
                        dev.nitka.nodewire.graph.Pin("x", "X", dev.nitka.nodewire.graph.PinType.FLOAT),
                        dev.nitka.nodewire.graph.Pin("y", "Y", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    if (!isVec2) xs.add(
                        dev.nitka.nodewire.graph.Pin("z", "Z", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    newInputs = xs
                    newOutputs = listOf(
                        dev.nitka.nodewire.graph.Pin("out", "Out", vecType),
                    )
                } else {
                    // vec_split
                    newInputs = listOf(
                        dev.nitka.nodewire.graph.Pin("in", "In", vecType),
                    )
                    val outs = mutableListOf(
                        dev.nitka.nodewire.graph.Pin("x", "X", dev.nitka.nodewire.graph.PinType.FLOAT),
                        dev.nitka.nodewire.graph.Pin("y", "Y", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    if (!isVec2) outs.add(
                        dev.nitka.nodewire.graph.Pin("z", "Z", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    newOutputs = outs
                }
                val newConfig = n.config.copy().apply { putString("dim", newDim) }
                n.copy(inputs = newInputs, outputs = newOutputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }
```

- [ ] **Step 2: Replace VecMake / VecSplit UI stubs with real implementations**

In `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`, replace the two stub lines (`val VecMake: @Composable (Node) -> Unit = { _ -> }` and the same for `VecSplit`) with:

```kotlin
    /**
     * VecMake: a single Select dim picker. Switching dim adds/removes the
     * z input pin and changes output pin type (handled by [EditorState.changeVecMakeSplitDim]).
     */
    val VecMake: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    dim = next
                    editor?.changeVecMakeSplitDim(node.id, next)
                },
                label = { it.lowercase() },
            )
        }
    }

    /** VecSplit: same Select as VecMake; output pins reshape. */
    val VecSplit: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        LabeledRow("Dim") {
            Select(
                options = VEC_DIMS,
                selected = dim,
                onSelect = { next ->
                    dim = next
                    editor?.changeVecMakeSplitDim(node.id, next)
                },
                label = { it.lowercase() },
            )
        }
    }

    private val VEC_DIMS = listOf("VEC2", "VEC3")
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecMake/VecSplit dim Select + pin reshape

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 17: `changeVecOp` mutator (op + dim) with pin reshape

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add `pinsForVecOp` helper + `changeVecOp` mutator**

Append to `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`, right after `changeVecMakeSplitDim`:

```kotlin
    /**
     * VecOp node: rebuild the input/output pin list from (op, dim) and
     * write both into config. Dim-locked ops (CROSS=VEC3, ROTATE2D=VEC2,
     * TO_VEC3 inputs VEC2→VEC3, TO_VEC2 inputs VEC3→VEC2) overwrite
     * caller's [dim].
     */
    fun changeVecOp(
        id: dev.nitka.nodewire.graph.NodeId,
        op: String,
        dim: String,  // "VEC2" / "VEC3"; ignored for locked ops
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val (effectiveDim, ins, outs) = pinsForVecOp(op, dim)
                val newConfig = n.config.copy().apply {
                    putString("op", op)
                    putString("dim", effectiveDim)
                }
                n.copy(inputs = ins, outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * For a (op, dim), return the canonical (effectiveDim, inputs, outputs)
     * triple. Op categories:
     *   * Vec→Vec binary: a, b ∈ V → out:V
     *   * Vec→Vec unary: v ∈ V → out:V
     *   * Scalar-mixed: SCALE(v, s), CLAMP_MAG(v, max), LERP(a, b, t)
     *   * Vec→Vec mixed: PROJECT(a, b), REFLECT(v, n)
     *   * Reductions: DOT/DISTANCE/ANGLE binary; LENGTH/LENGTH_SQ unary
     *   * Dim-locked: CROSS=VEC3, ROTATE2D=VEC2
     *   * Conversions: TO_VEC3 (v:VEC2, z:FLOAT → VEC3), TO_VEC2 (v:VEC3 → VEC2)
     */
    private fun pinsForVecOp(
        op: String,
        dim: String,
    ): Triple<String, List<dev.nitka.nodewire.graph.Pin>, List<dev.nitka.nodewire.graph.Pin>> {
        val pin = dev.nitka.nodewire.graph.Pin
        val P = dev.nitka.nodewire.graph.PinType
        val effectiveDim = when (op) {
            "CROSS" -> "VEC3"
            "ROTATE2D" -> "VEC2"
            "TO_VEC3", "TO_VEC2" -> dim  // unused at evaluator level
            else -> if (dim == "VEC3") "VEC3" else "VEC2"
        }
        val V = if (effectiveDim == "VEC3") P.VEC3 else P.VEC2
        return when (op) {
            // Binary vec→vec
            "ADD", "SUB", "MUL_COMPONENT", "MIN", "MAX" -> Triple(
                effectiveDim,
                listOf(pin("a", "A", V), pin("b", "B", V)),
                listOf(pin("out", "Out", V)),
            )
            // Unary vec→vec
            "NEGATE", "NORMALIZE", "ABS" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", V)),
                listOf(pin("out", "Out", V)),
            )
            "SCALE" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", V), pin("s", "S", P.FLOAT)),
                listOf(pin("out", "Out", V)),
            )
            "CLAMP_MAG" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", V), pin("max", "Max", P.FLOAT)),
                listOf(pin("out", "Out", V)),
            )
            "LERP" -> Triple(
                effectiveDim,
                listOf(pin("a", "A", V), pin("b", "B", V), pin("t", "T", P.FLOAT)),
                listOf(pin("out", "Out", V)),
            )
            "PROJECT" -> Triple(
                effectiveDim,
                listOf(pin("a", "A", V), pin("b", "B", V)),
                listOf(pin("out", "Out", V)),
            )
            "REFLECT" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", V), pin("n", "N", V)),
                listOf(pin("out", "Out", V)),
            )
            // Reductions → FLOAT out
            "DOT", "DISTANCE", "ANGLE" -> Triple(
                effectiveDim,
                listOf(pin("a", "A", V), pin("b", "B", V)),
                listOf(pin("out", "Out", P.FLOAT)),
            )
            "LENGTH", "LENGTH_SQ" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", V)),
                listOf(pin("out", "Out", P.FLOAT)),
            )
            // Dim-locked
            "CROSS" -> Triple(
                "VEC3",
                listOf(pin("a", "A", P.VEC3), pin("b", "B", P.VEC3)),
                listOf(pin("out", "Out", P.VEC3)),
            )
            "ROTATE2D" -> Triple(
                "VEC2",
                listOf(pin("v", "V", P.VEC2), pin("angle", "Angle", P.FLOAT)),
                listOf(pin("out", "Out", P.VEC2)),
            )
            // Conversions
            "TO_VEC3" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", P.VEC2), pin("z", "Z", P.FLOAT)),
                listOf(pin("out", "Out", P.VEC3)),
            )
            "TO_VEC2" -> Triple(
                effectiveDim,
                listOf(pin("v", "V", P.VEC3)),
                listOf(pin("out", "Out", P.VEC2)),
            )
            // Unknown op → fallback to ADD shape
            else -> Triple(
                effectiveDim,
                listOf(pin("a", "A", V), pin("b", "B", V)),
                listOf(pin("out", "Out", V)),
            )
        }
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "$(cat <<'EOF'
feat(vector): EditorState.changeVecOp + pin reshape table

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 18: `VecOp` config UI (op + dim selects)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Replace VecOp stub with real implementation**

In `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`, replace the `val VecOp: @Composable (Node) -> Unit = { _ -> }` stub with:

```kotlin
    /**
     * VecOp: op picker + dim picker. Dim is disabled (locked) when the
     * selected op forces a specific dimension (CROSS=VEC3, ROTATE2D=VEC2,
     * TO_VEC3/TO_VEC2 ignore dim).
     */
    val VecOp: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var op by remember(node.id) {
            mutableStateOf(node.config.getString("op").ifEmpty { "ADD" })
        }
        var dim by remember(node.id) {
            mutableStateOf(node.config.getString("dim").ifEmpty { "VEC2" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            LabeledRow("Op") {
                Select(
                    options = VEC_OPS,
                    selected = op,
                    onSelect = { next ->
                        op = next
                        editor?.changeVecOp(node.id, next, dim)
                    },
                    label = { it.lowercase() },
                )
            }
            if (!isVecOpDimLocked(op)) {
                LabeledRow("Dim") {
                    Select(
                        options = VEC_DIMS,
                        selected = dim,
                        onSelect = { next ->
                            dim = next
                            editor?.changeVecOp(node.id, op, next)
                        },
                        label = { it.lowercase() },
                    )
                }
            }
        }
    }

    /** Full op catalog, grouped roughly the way users think: binary first,
     *  then unary, scalar-mixed, reductions, dim-specific, conversions. */
    private val VEC_OPS = listOf(
        "ADD", "SUB", "MUL_COMPONENT", "MIN", "MAX",
        "NEGATE", "NORMALIZE", "ABS",
        "SCALE", "CLAMP_MAG", "LERP", "PROJECT", "REFLECT",
        "DOT", "LENGTH", "LENGTH_SQ", "DISTANCE", "ANGLE",
        "CROSS", "ROTATE2D", "TO_VEC3", "TO_VEC2",
    )

    private fun isVecOpDimLocked(op: String): Boolean =
        op == "CROSS" || op == "ROTATE2D" || op == "TO_VEC3" || op == "TO_VEC2"
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(vector): VecOp config UI (op + dim selects)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 19: Constant VEC2 body composable

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Add `ConstantBodyVec2` + wire into Constant dispatcher**

Edit `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`:

1) Add `ConstantBodyVec2` near `ConstantBodyVec3` (around line 405):

```kotlin
    @Composable
    private fun ConstantBodyVec2(node: Node, editor: EditorState?) {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            FloatField(node, "x2", "X", editor)
            FloatField(node, "y2", "Y", editor)
        }
    }
```

2) Insert `VEC2` branch in the `when (type)` dispatcher in `Constant` (around line 434–441) so it reads:

```kotlin
            when (type) {
                PinType.BOOL -> ConstantBodyBool(node, editor)
                PinType.INT -> ConstantBodyInt(node, editor)
                PinType.FLOAT -> ConstantBodyFloat(node, editor)
                PinType.STRING -> ConstantBodyString(node, editor)
                PinType.VEC2 -> ConstantBodyVec2(node, editor)
                PinType.VEC3 -> ConstantBodyVec3(node, editor)
                else -> Unit
            }
```

3) Add `VEC2` to `CONSTANT_TYPES` list:

```kotlin
    private val CONSTANT_TYPES = listOf(
        PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.STRING,
        PinType.VEC2, PinType.VEC3,
    )
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(constant): VEC2 body in Constant picker

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 7 — Pin-reshape integration tests

### Task 20: `VecOpPinReshapeTest` — changeVecOp drops incompatible edges

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/VecOpPinReshapeTest.kt`

- [ ] **Step 1: Write failing test**

`EditorState` has the signature `EditorState(graph: NodeGraph, pos: BlockPos = BlockPos.ZERO)` (confirmed in `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt:33`). It runs synchronously for mutators — no Compose/Coroutine context needed for these tests.

Create `src/test/kotlin/dev/nitka/nodewire/graph/VecOpPinReshapeTest.kt`:

```kotlin
package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.EditorState
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VecOpPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    private fun newEditor(): Pair<EditorState, NodeGraph> {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        return es to g
    }

    @Test fun defaultOpAddVec2HasTwoVec2InputsAndOneVec2Output() {
        val (_, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        g.add(n)
        assertEquals(2, n.inputs.size)
        assertEquals(PinType.VEC2, n.inputs[0].type)
        assertEquals(PinType.VEC2, n.outputs[0].type)
    }

    @Test fun changeOpToLengthCollapsesToOneInputFloatOutput() {
        val (es, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        g.add(n)
        es.changeVecOp(n.id, "LENGTH", "VEC2")
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(1, refreshed.inputs.size)
        assertEquals("v", refreshed.inputs[0].id)
        assertEquals(PinType.VEC2, refreshed.inputs[0].type)
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
    }

    @Test fun changeOpToCrossForcesVec3() {
        val (es, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        g.add(n)
        es.changeVecOp(n.id, "CROSS", "VEC2")  // caller asked VEC2; CROSS overrides
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals("VEC3", refreshed.config.getString("dim"))
        assertEquals(PinType.VEC3, refreshed.inputs[0].type)
        assertEquals(PinType.VEC3, refreshed.outputs[0].type)
    }

    @Test fun changeOpDropsIncompatibleEdges() {
        val (es, g) = newEditor()
        val a = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", PinType.VEC2.name)
        }
        val op = VectorNodeTypes.VEC_OP.newInstance()  // ADD on VEC2 by default
        g.add(a); g.add(op)
        g.addEdge(Edge(PinRef(a.id, "out"), PinRef(op.id, "a")))
        assertEquals(1, g.edges.size)
        // Switch to LENGTH on VEC3 — "a" pin no longer exists (now "v"),
        // and types mismatch → edge dropped.
        es.changeVecOp(op.id, "LENGTH", "VEC3")
        assertTrue(g.edges.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VecOpPinReshapeTest"`
Expected: 4 tests pass.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, full suite green.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/graph/VecOpPinReshapeTest.kt
git commit -m "$(cat <<'EOF'
test(vector): VecOp pin reshape + edge dropping

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 21: `VecMakeSplitPinReshapeTest`

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/VecMakeSplitPinReshapeTest.kt`

- [ ] **Step 1: Write test**

Create:

```kotlin
package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.EditorState
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VecMakeSplitPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    @Test fun vecMakeSwitchToVec3AddsZInput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = VectorNodeTypes.VEC_MAKE.newInstance()
        g.add(n)
        assertEquals(2, n.inputs.size)
        es.changeVecMakeSplitDim(n.id, "VEC3")
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(3, refreshed.inputs.size)
        assertEquals("z", refreshed.inputs[2].id)
        assertEquals(PinType.VEC3, refreshed.outputs[0].type)
    }

    @Test fun vecSplitSwitchToVec3AddsZOutput() {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        val n = VectorNodeTypes.VEC_SPLIT.newInstance()
        g.add(n)
        assertEquals(2, n.outputs.size)
        es.changeVecMakeSplitDim(n.id, "VEC3")
        val refreshed = g.nodes.first { it.id == n.id }
        assertEquals(3, refreshed.outputs.size)
        assertEquals(PinType.VEC3, refreshed.inputs[0].type)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.VecMakeSplitPinReshapeTest"`
Expected: 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/graph/VecMakeSplitPinReshapeTest.kt
git commit -m "$(cat <<'EOF'
test(vector): VecMake/VecSplit pin reshape on dim change

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 8 — Final validation

### Task 22: Full build + test + manual-test checklist

**Files:** none modified.

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`. Total tests should be original count + 45-ish new (≈43 evaluator + 2 reshape Make/Split + 4 reshape VecOp).

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Stop. Do NOT run `runClient` yourself.**

Report the following manual test plan to the user:

```
Manual test plan for Vector Toolkit (run ./gradlew runClient yourself):

1. Open Logic Block → Tab-search "vec" → confirm "Vec Make", "Vec Split",
   "Vec Op" appear under a "Vector" header with purple-tinted title bar.

2. Spawn Vec Make → default dim=VEC2 → two FLOAT inputs (X, Y), one
   VEC2 output. Change dim Select → VEC3 → third input "Z" appears,
   output becomes VEC3. Change back to VEC2 → "Z" disappears.

3. Spawn Vec Split → mirror: 1 vec input, 2/3 float outputs based on dim.

4. Spawn Vec Op (default ADD/VEC2). Verify:
   - "Op" Select shows all 22 ops in the documented order.
   - "Dim" Select present for ADD/SUB/etc.
   - Pick CROSS → Dim Select hides, pins force VEC3.
   - Pick ROTATE2D → Dim hides, pins force VEC2, second input is FLOAT "Angle".
   - Pick LENGTH → 1 vec input, 1 FLOAT output.
   - Pick TO_VEC3 → input v:VEC2, z:FLOAT, output VEC3.

5. Build a chain: CONSTANT(VEC2, x2=3, y2=4) → vec_split → x to a
   CONSTANT(FLOAT, float=value); verify pin-value chip on output reads
   "(3.0; 4.0)" then "3.0f". Then chain into a vec_op LENGTH → expect
   5.0 if you wire both back through a make → length.

6. Spawn CONSTANT → switch type to VEC2 → two FLOAT fields (X, Y).
   Switching to VEC3 still shows three fields (existing behavior preserved).

7. Save+reload the block (break / replace, or close+reopen world):
   vec_op / vec_make / vec_split nodes survive with their config intact.

If any of the above fail, report which step + screenshot. Don't fix
yourself — flag back.
```

- [ ] **Step 4: Commit final touch (no code changes, but log the manual checklist into the plan)**

No commit needed for this step — manual testing is on the user.

---

# Out of scope (do NOT do in this plan)

- Tweaked Controller integration — separate plan `2026-05-17-tweaked-controller-integration.md`.
- Quat conversions, Euler angles, swizzling.
- Vector input handler shortcuts (Vec2 / Vec3 don't appear as standalone "fast" pin types in TextInput; users build them via Vec Make).
- i18n / Ukrainian translations (display names are English).
- Special wire colors per dim — the existing `pinVec2`/`pinVec3` palette is reused.
