# From Redstone Node Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `From Redstone` configurable converter node — REDSTONE input → typed output (INT/FLOAT/BOOL) driven by `config.targetType` and `config.mode`. Mirrors the existing `ConvertToRedstone` shape.

**Architecture:** New `StockNodeTypes.FROM_REDSTONE` + `StockEvaluators.FromRedstone`. New `NodeConfigContent.FromRedstone` Composable. New `EditorState.changeFromRedstoneOutput`. Registered in CONVERSION group. No changes to existing CTR.

**Tech Stack:** Kotlin 2.0.20, compose-runtime 1.7.0, JUnit 5, Mojang Codec for NBT.

**Spec:** `docs/superpowers/specs/2026-05-15-from-redstone-node.md`

**Stay on master branch.** Do not create a feature branch.

---

### Task 1: Evaluator (TDD)

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsFromRedstoneTest.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` (append `FromRedstone` near `ConvertToRedstone`)

**Context:** `StockEvaluators.kt` defines `NodeEvaluator` lambdas. `ConvertToRedstone` (around line 205) shows the pattern: read config strings + mode-dispatched `when` blocks + return `mapOf("out" to PinValue.<Type>(v))`. Helpers `intIn`/`floatIn`/`boolIn`/`redstoneIn` already exist for typed input reads — verify the redstone reader name in the file before writing the test; the input value will be an `Int` 0..15.

- [ ] **Step 1: Write the failing test class**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsFromRedstoneTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    private fun signalIn(v: Int): Map<String, PinValue> =
        mapOf("in" to PinValue.Redstone(v))

    @Test
    fun intRawPassesThrough() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "INT"); putString("mode", "raw") },
            signalIn(7),
        )
        assertEquals(PinValue.Int(7), out["out"])
    }

    @Test
    fun intScaledLerpsIntoRange() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "INT"); putString("mode", "scaled")
                putInt("min", 0); putInt("max", 100)
            },
            signalIn(15),
        )
        assertEquals(PinValue.Int(100), out["out"])
    }

    @Test
    fun floatNormalizedDividesBy15() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "FLOAT"); putString("mode", "normalized") },
            signalIn(15),
        )
        assertEquals(PinValue.Float(1.0f), out["out"])
    }

    @Test
    fun floatRawCastsToFloat() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "FLOAT"); putString("mode", "raw") },
            signalIn(7),
        )
        assertEquals(PinValue.Float(7.0f), out["out"])
    }

    @Test
    fun floatScaledLerps() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "FLOAT"); putString("mode", "scaled")
                putFloat("min", -1f); putFloat("max", 1f)
            },
            signalIn(0),
        )
        assertEquals(PinValue.Float(-1.0f), out["out"])
    }

    @Test
    fun boolAnyIsSignalGtZero() {
        val out = StockEvaluators.FromRedstone(
            cfg { putString("targetType", "BOOL"); putString("mode", "any") },
            signalIn(1),
        )
        assertEquals(PinValue.Bool(true), out["out"])
    }

    @Test
    fun boolThresholdRespectsConfig() {
        val out = StockEvaluators.FromRedstone(
            cfg {
                putString("targetType", "BOOL"); putString("mode", "threshold")
                putInt("threshold", 8)
            },
            signalIn(7),
        )
        assertEquals(PinValue.Bool(false), out["out"])
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsFromRedstoneTest"`
Expected: FAIL — `StockEvaluators.FromRedstone` does not exist.

- [ ] **Step 3: Implement the evaluator**

In `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`, add immediately after `ConvertToRedstone`:

```kotlin
/**
 * From-Redstone: vanilla redstone signal (0..15) → typed output. The
 * output type and conversion behavior are driven by `config.targetType`
 * and `config.mode`. Mirrors [ConvertToRedstone].
 *
 * Modes:
 *   * INT/raw          → signal
 *   * INT/scaled       → lerp(signal, 0..15 → min..max), int
 *   * FLOAT/normalized → signal / 15f
 *   * FLOAT/raw        → signal.toFloat()
 *   * FLOAT/scaled     → lerp(signal, 0..15 → min..max), float
 *   * BOOL/any         → signal > 0
 *   * BOOL/threshold   → signal >= config.threshold
 */
val FromRedstone: NodeEvaluator = { config, inputs ->
    val signal = redstoneIn(inputs, "in").coerceIn(0, 15)
    val targetType = config.getString("targetType").ifEmpty { "INT" }
    val mode = config.getString("mode")
    val out: PinValue = when (targetType) {
        "INT" -> when (mode) {
            "scaled" -> {
                val lo = config.getInt("min"); val hi = config.getInt("max")
                val v = if (hi == lo) lo
                else (lo + ((signal.toFloat() / 15f) * (hi - lo)).toInt())
                PinValue.Int(v)
            }
            else -> PinValue.Int(signal) // "raw"
        }
        "FLOAT" -> when (mode) {
            "raw" -> PinValue.Float(signal.toFloat())
            "scaled" -> {
                val lo = config.getFloat("min"); val hi = config.getFloat("max")
                val v = if (hi == lo) lo else lo + (signal / 15f) * (hi - lo)
                PinValue.Float(v)
            }
            else -> PinValue.Float(signal / 15f) // "normalized"
        }
        "BOOL" -> when (mode) {
            "threshold" -> PinValue.Bool(signal >= config.getInt("threshold"))
            else -> PinValue.Bool(signal > 0) // "any"
        }
        else -> PinValue.Int(signal)
    }
    mapOf("out" to out)
}
```

**Verify before committing:** open `StockEvaluators.kt` and confirm the redstone-input helper is named `redstoneIn`. If it's named differently (e.g. `redIn`, `signalIn`), substitute the correct name. If no helper exists, read the value directly: `val signal = (inputs["in"] as? PinValue.Redstone)?.value ?: 0`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsFromRedstoneTest"`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsFromRedstoneTest.kt
git commit -m "feat(graph): FromRedstone evaluator with INT/FLOAT/BOOL targets"
```

---

### Task 2: Register the node type

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`

**Context:** `StockNodeTypes` builds `NodeType` entries via the private `nodeType(...)` helper. `CONVERT_TO_REDSTONE` is around line 88. `registerAll()` aggregates all types in a `listOf(...)` near line 392 grouped by category comment (`// Conversion` section currently lists `INT_TO_FLOAT, FLOAT_TO_INT, BOOL_TO_INT, INT_TO_BOOL, CONVERT_TO_REDSTONE`).

- [ ] **Step 1: Add the FROM_REDSTONE constant**

Insert immediately after `CONVERT_TO_REDSTONE`:

```kotlin
val FROM_REDSTONE = nodeType(
    id = "from_redstone",
    displayName = "From Redstone",
    category = NodeCategory.CONVERSION,
    inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
    outputs = listOf(Pin("out", "Out", PinType.INT)),
    defaultConfig = {
        CompoundTag().apply {
            putString("targetType", PinType.INT.name)
            putString("mode", "raw")
            putInt("threshold", 1)
            putInt("min", 0)
            putInt("max", 15)
        }
    },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.FromRedstone,
    evaluate = StockEvaluators.FromRedstone,
)
```

- [ ] **Step 2: Add to `registerAll()`**

Change the `// Conversion` line in the `listOf(...)` from:

```kotlin
INT_TO_FLOAT, FLOAT_TO_INT, BOOL_TO_INT, INT_TO_BOOL, CONVERT_TO_REDSTONE,
```

to:

```kotlin
INT_TO_FLOAT, FLOAT_TO_INT, BOOL_TO_INT, INT_TO_BOOL, CONVERT_TO_REDSTONE, FROM_REDSTONE,
```

- [ ] **Step 3: Intentional intermediate compile state**

After this task, `StockNodeTypes.kt` references `NodeConfigContent.FromRedstone` which does not yet exist. The compile fix lands in Task 4. To unblock Task 3 (which only touches `EditorState.kt` + its test, no compose deps), commit with `--no-verify` since the build will fail until Task 4.

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt
git commit --no-verify -m "feat(graph): register FROM_REDSTONE node type"
```

---

### Task 3: EditorState mutator (TDD)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFromRedstoneTest.kt`

**Context:** `EditorState.changeConverterInput` (around line 237) is the template — rebuilds the single input pin and disconnects all edges touching the node. The new mutator does the same but for the single output pin. There's already an existing `EditorStateFlowTest` in the same package showing how to construct test nodes and call mutators directly. `disconnectAllEdges` is a private helper that already emits `_edges.value`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateFromRedstoneTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun registerTypes() {
            StockNodeTypes.registerAll()
        }
    }

    @Test
    fun changeFromRedstoneOutputRebuildsPinAndDisconnects() {
        val graph = NodeGraph()
        val from = StockNodeTypes.FROM_REDSTONE.newInstance(dev.nitka.nodewire.graph.CanvasPos.Zero)
        val sink = StockNodeTypes.INT_CONST.newInstance(dev.nitka.nodewire.graph.CanvasPos.Zero)
        // Use a node with an INT input — pick an existing one with one int input.
        val intConsumer = StockNodeTypes.CONVERT_TO_REDSTONE.newInstance(dev.nitka.nodewire.graph.CanvasPos.Zero)
        graph.add(from); graph.add(intConsumer)
        graph.addEdge(Edge(PinRef(from.id, "out"), PinRef(intConsumer.id, "in")))

        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeFromRedstoneOutput(from.id, PinType.BOOL)

        val updated = editor.nodeFlow(from.id)!!.value
        assertEquals(PinType.BOOL, updated.outputs.first().type)
        assertTrue(editor.edges.value.none { it.from.node == from.id })
    }
}
```

If `EditorState.kt`'s constructor signature differs from `(graph, BlockPos)`, mirror what `EditorStateFlowTest` does — read that file first and copy its setup verbatim.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateFromRedstoneTest"`
Expected: FAIL — `changeFromRedstoneOutput` not defined.

- [ ] **Step 3: Implement the mutator**

In `EditorState.kt`, immediately after `changeConverterInput`:

```kotlin
/**
 * From-Redstone has a single output pin whose type follows
 * `config.targetType`. Rebuild the pin and snip incompatible edges.
 */
fun changeFromRedstoneOutput(
    id: dev.nitka.nodewire.graph.NodeId,
    newType: dev.nitka.nodewire.graph.PinType,
) {
    updateNode(id) { n ->
        n.copy(outputs = listOf(n.outputs.first().copy(type = newType)))
    }
    disconnectAllEdges(id)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateFromRedstoneTest"`
Expected: PASS.

(The build still won't link the whole project until Task 4 — but JVM-side test classes that don't transitively touch `NodeConfigContent.FromRedstone` will compile and run.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFromRedstoneTest.kt
git commit --no-verify -m "feat(editor): changeFromRedstoneOutput rebuilds output pin"
```

`--no-verify` again because the whole-project compile still fails until Task 4 lands.

---

### Task 4: Config UI Composable

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

**Context:** `NodeConfigContent.ConvertToRedstone` (around line 298) is the template. Private helpers `LabeledRow`, `Select`, `IntField`, `FloatField` already exist in this file; reuse them. `Column`/`Arrangement.spacedBy`/`NwTheme.dimens.space2` are also already imported. The CTR helpers are private and live at the bottom of the file — `SOURCE_TYPES`, `defaultModeFor`, `modesFor`, `ModeParams`. Add parallel `TARGET_TYPES`, `defaultTargetModeFor`, `modesForTarget`, `FromRedstoneModeParams`.

- [ ] **Step 1: Add the FromRedstone Composable**

Immediately after the `ConvertToRedstone` val (look for `private val SOURCE_TYPES` — insert above that), add:

```kotlin
/**
 * FromRedstone: target type + mode pickers. Mirror of [ConvertToRedstone]
 * for the inverse direction. The output pin rebuilds via
 * [EditorState.changeFromRedstoneOutput] which also snips outgoing edges.
 */
val FromRedstone: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var targetType by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("targetType").ifEmpty { PinType.INT.name }))
    }
    var mode by remember(node.id) {
        mutableStateOf(node.config.getString("mode").ifEmpty { defaultTargetModeFor(targetType) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        LabeledRow("To") {
            Select(
                options = TARGET_TYPES,
                selected = targetType,
                onSelect = { next ->
                    val defaultMode = defaultTargetModeFor(next)
                    targetType = next
                    mode = defaultMode
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putString("targetType", next.name)
                            putString("mode", defaultMode)
                        })
                    }
                    editor?.changeFromRedstoneOutput(node.id, next)
                },
                label = { it.name.lowercase() },
            )
        }
        val modes = modesForTarget(targetType)
        LabeledRow("Mode") {
            Select(
                options = modes,
                selected = mode,
                onSelect = { next ->
                    mode = next
                    editor?.updateNode(node.id) { n ->
                        n.copy(config = n.config.copy().apply {
                            putString("mode", next)
                        })
                    }
                },
                label = { it },
            )
        }
        FromRedstoneModeParams(node, targetType, mode, editor)
    }
}
```

- [ ] **Step 2: Add the helpers**

Below the existing `ModeParams` function (around line 380), append:

```kotlin
private val TARGET_TYPES = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)

private fun defaultTargetModeFor(t: PinType) = when (t) {
    PinType.INT -> "raw"
    PinType.FLOAT -> "normalized"
    PinType.BOOL -> "any"
    else -> "raw"
}

private fun modesForTarget(t: PinType): List<String> = when (t) {
    PinType.INT -> listOf("raw", "scaled")
    PinType.FLOAT -> listOf("normalized", "raw", "scaled")
    PinType.BOOL -> listOf("any", "threshold")
    else -> listOf("raw")
}

@Composable
private fun FromRedstoneModeParams(node: Node, targetType: PinType, mode: String, editor: EditorState?) {
    when {
        targetType == PinType.INT && mode == "scaled" -> {
            IntField(node, "min", "Min", editor)
            IntField(node, "max", "Max", editor)
        }
        targetType == PinType.FLOAT && mode == "scaled" -> {
            FloatField(node, "min", "Min", editor)
            FloatField(node, "max", "Max", editor)
        }
        targetType == PinType.BOOL && mode == "threshold" ->
            IntField(node, "threshold", "Threshold", editor)
    }
}
```

- [ ] **Step 3: Build the whole project**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All tests pass (including the Task 1 & Task 3 tests which were previously gated behind the compile failure).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "feat(ui): FromRedstone config composable"
```

---

### Task 5: Final build verification

**Files:** none modified.

- [ ] **Step 1: Run the full build + test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: List the 4 implementation commits**

Run: `git log --oneline -n 6`
Expected to see (most recent first):
- feat(ui): FromRedstone config composable
- feat(editor): changeFromRedstoneOutput rebuilds output pin
- feat(graph): register FROM_REDSTONE node type
- feat(graph): FromRedstone evaluator with INT/FLOAT/BOOL targets
- docs: spec for From Redstone configurable converter node

- [ ] **Step 3: Hand-off note**

User should smoke-test in-game:
- Open editor, right-click → add `From Redstone` node.
- Set target type to BOOL → output pin should rebuild as BOOL.
- Connect a Side Input (REDSTONE) to its input, and the BOOL output downstream.
- Confirm: changing mode `any` → `threshold` exposes a Threshold field.
- Confirm: changing target FLOAT → exposes `normalized`/`raw`/`scaled` mode options.
