# Node Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse 27 type/op-specific stock nodes into 5 configurable nodes (`constant`, `logic_gate`, `math`, `compare`, `convert`). Clean slate — no aliases for the deleted IDs.

**Architecture:** Each phase implements one consolidated node end-to-end (evaluator, registry entry, EditorState mutator, config UI Composable, deletion of obsolete originals, callsite updates), commits a single green build. Phase order: Constant → LogicGate → Math → Compare → Convert → final cleanup.

**Tech Stack:** Kotlin 2.0.20, compose-runtime 1.7.0, JUnit 5, Mojang Codec for NBT.

**Spec:** `docs/superpowers/specs/2026-05-15-node-consolidation.md`

**Stay on master branch.**

**Conventions for every phase:**
- TDD: write a failing test, then implement, then green.
- Each phase's last step is `./gradlew build` green + one commit.
- Mutator pattern mirrors `EditorState.changeFromRedstoneOutput` — `updateNode(id) { copy(...) }` then `disconnectAllEdges(id)`.
- UI Composable pattern mirrors `NodeConfigContent.FromRedstone` — `Select` rows + `remember(node.id) { mutableStateOf(...) }` for local UI state.
- Deleted node IDs are gone — no aliases. Graphs saved with old IDs lose those nodes on load.
- After deleting an old node, immediately update **all** callsites (run `grep -rn "<OLD_ID>" src` until empty).
- Verify with `./gradlew build` at end of each phase. Must be BUILD SUCCESSFUL with all tests green before commit.

**Callsites known up-front** (you will rediscover via grep — list for reference):
- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — definitions + `registerAll()`.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` — evaluator lambdas.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — UI Composables.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — mutators.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` — `seedIfEmpty()` uses `BOOL_CONST`/`INT_CONST`/`AND` etc.
- `src/main/kotlin/dev/nitka/nodewire/ui/dev/DemoScreen.kt` — uses `BOOL_CONST`/`INT_CONST`/`STRING_CONST`/`ADD_INT`/`COMPARE_INT`/`AND`.
- `src/test/kotlin/dev/nitka/nodewire/graph/StockNodeTypesTest.kt` — `COMPARE_INT`/`INT_CONST`.
- `src/test/kotlin/dev/nitka/nodewire/graph/GraphEvaluatorTest.kt` — `BOOL_CONST`/`INT_CONST`/`AND`/`NOT`/`ADD_INT`/`COMPARE_INT`.

---

## Phase 1: Constant node

**Replaces:** `BOOL_CONST`, `INT_CONST`, `FLOAT_CONST`, `STRING_CONST`, `VEC3_CONST`.

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` (seedIfEmpty)
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/dev/DemoScreen.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/StockNodeTypesTest.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/GraphEvaluatorTest.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConstantTest.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateConstantTest.kt`

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
// StockEvaluatorsConstantTest.kt
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConstantTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun boolReadsFromBoolSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "BOOL"); putBoolean("bool", true) },
            emptyMap(),
        )
        assertEquals(PinValue.Bool(true), out["out"])
    }

    @Test fun intReadsFromIntSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "INT"); putInt("int", 42) },
            emptyMap(),
        )
        assertEquals(PinValue.Int(42), out["out"])
    }

    @Test fun floatReadsFromFloatSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "FLOAT"); putFloat("float", 3.14f) },
            emptyMap(),
        )
        assertEquals(PinValue.Float(3.14f), out["out"])
    }

    @Test fun stringReadsFromStringSlot() {
        val out = StockEvaluators.Constant(
            cfg { putString("type", "STRING"); putString("string", "hi") },
            emptyMap(),
        )
        assertEquals(PinValue.Str("hi"), out["out"])
    }

    @Test fun vec3ReadsFromXYZSlots() {
        val out = StockEvaluators.Constant(
            cfg {
                putString("type", "VEC3")
                putFloat("x", 1f); putFloat("y", 2f); putFloat("z", 3f)
            },
            emptyMap(),
        )
        assertEquals(PinValue.Vec3F(1f, 2f, 3f), out["out"])
    }

    @Test fun unknownTypeFallsBackToBoolFalse() {
        val out = StockEvaluators.Constant(cfg { }, emptyMap())
        assertEquals(PinValue.Bool(false), out["out"])
    }
}
```

**Important:** Before running, open `src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt` and confirm the exact subclass names for String and Vec3. The test uses `PinValue.Str` and `PinValue.Vec3F` placeholders — substitute whatever names the file actually uses. Likewise verify `PinValue.default(PinType.BOOL)` returns `Bool(false)` (it should — this is the existing fallback in `ChannelInput`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsConstantTest"`
Expected: FAIL — `StockEvaluators.Constant` not defined.

- [ ] **Step 3: Implement the evaluator**

Append to `StockEvaluators.kt` (anywhere near other config-driven evaluators):

```kotlin
/**
 * Constant: outputs one of BOOL/INT/FLOAT/STRING/VEC3 driven by
 * `config.type`. Each type has its own config slot (so type-switching
 * preserves prior values). Unknown type → BOOL false.
 */
val Constant: NodeEvaluator = { config, _ ->
    val type = PinType.fromName(config.getString("type").ifEmpty { PinType.BOOL.name })
    val out: PinValue = when (type) {
        PinType.BOOL -> PinValue.Bool(config.getBoolean("bool"))
        PinType.INT -> PinValue.Int(config.getInt("int"))
        PinType.FLOAT -> PinValue.Float(config.getFloat("float"))
        PinType.STRING -> PinValue.Str(config.getString("string"))
        PinType.VEC3 -> PinValue.Vec3F(
            config.getFloat("x"), config.getFloat("y"), config.getFloat("z"),
        )
        else -> PinValue.default(PinType.BOOL)
    }
    mapOf("out" to out)
}
```

Adjust `PinValue.Str` / `PinValue.Vec3F` to match the actual class names.

- [ ] **Step 4: Add the EditorState mutator + test**

`EditorStateConstantTest.kt`:

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateConstantTest {
    companion object { @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() } }

    @Test fun changeConstantTypeRebuildsOutputPin() {
        val graph = NodeGraph()
        val n = StockNodeTypes.CONSTANT.newInstance(dev.nitka.nodewire.graph.CanvasPos.Zero)
        graph.add(n)
        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeConstantType(n.id, PinType.STRING)
        val updated = editor.nodeFlow(n.id)!!.value
        assertEquals(PinType.STRING, updated.outputs.first().type)
        assertEquals("STRING", updated.config.getString("type"))
    }
}
```

In `EditorState.kt`, add after `changeFromRedstoneOutput`:

```kotlin
fun changeConstantType(
    id: dev.nitka.nodewire.graph.NodeId,
    newType: dev.nitka.nodewire.graph.PinType,
) {
    updateNode(id) { n ->
        val rebuilt = n.outputs.first().copy(type = newType)
        val newConfig = n.config.copy().apply { putString("type", newType.name) }
        n.copy(outputs = listOf(rebuilt), config = newConfig)
    }
    disconnectAllEdges(id)
}
```

- [ ] **Step 5: Convert old per-type Composables into private helpers**

In `NodeConfigContent.kt`, find the existing public Composables `BoolConst`, `IntConst`, `FloatConst`, `StringConst`. Rename them to private and prefix with `ConstantBody`:

```kotlin
@Composable
private fun ConstantBodyBool(node: Node, editor: EditorState?) { /* old BoolConst body */ }

@Composable
private fun ConstantBodyInt(node: Node, editor: EditorState?) { /* old IntConst body */ }

@Composable
private fun ConstantBodyFloat(node: Node, editor: EditorState?) { /* old FloatConst body */ }

@Composable
private fun ConstantBodyString(node: Node, editor: EditorState?) { /* old StringConst body */ }
```

Their bodies wrote into `config.value` previously. Update each to write into its dedicated slot:
- Bool body → `config.getBoolean("bool")` / `putBoolean("bool", ...)`
- Int body → `config.getInt("int")` / `putInt("int", ...)`
- Float body → `config.getFloat("float")` / `putFloat("float", ...)`
- String body → `config.getString("string")` / `putString("string", ...)`

Also add a new `ConstantBodyVec3` writing into `x`, `y`, `z` — three `FloatField`s.

- [ ] **Step 6: Add the public `Constant` Composable**

In `NodeConfigContent.kt`, near the other public converter Composables:

```kotlin
val Constant: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var type by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.BOOL.name }))
    }
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        LabeledRow("Type") {
            Select(
                options = CONSTANT_TYPES,
                selected = type,
                onSelect = { next ->
                    type = next
                    editor?.changeConstantType(node.id, next)
                },
                label = { it.name.lowercase() },
            )
        }
        when (type) {
            PinType.BOOL -> ConstantBodyBool(node, editor)
            PinType.INT -> ConstantBodyInt(node, editor)
            PinType.FLOAT -> ConstantBodyFloat(node, editor)
            PinType.STRING -> ConstantBodyString(node, editor)
            PinType.VEC3 -> ConstantBodyVec3(node, editor)
            else -> Unit
        }
    }
}

private val CONSTANT_TYPES = listOf(
    PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.STRING, PinType.VEC3,
)
```

- [ ] **Step 7: Register `CONSTANT` and delete the 5 old nodes**

In `StockNodeTypes.kt`:

1. Replace the 5 individual `BOOL_CONST` / `INT_CONST` / `FLOAT_CONST` / `STRING_CONST` / `VEC3_CONST` blocks with one:

```kotlin
val CONSTANT = nodeType(
    id = "constant",
    displayName = "Constant",
    category = NodeCategory.CONSTANTS,
    outputs = listOf(Pin("out", "Value", PinType.BOOL)),
    defaultConfig = {
        CompoundTag().apply {
            putString("type", PinType.BOOL.name)
            putBoolean("bool", false)
            putInt("int", 0)
            putFloat("float", 0f)
            putString("string", "")
            putFloat("x", 0f); putFloat("y", 0f); putFloat("z", 0f)
        }
    },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Constant,
    evaluate = StockEvaluators.Constant,
)
```

2. In `registerAll()`, in the `// Constants` group, replace the 5 old IDs with `CONSTANT`. Keep `TIMER`, `RANDOM_BOOL`, `RANDOM_INT`, `PULSE` (those stay).

- [ ] **Step 8: Remove obsolete evaluators**

In `StockEvaluators.kt`, delete `BoolConst`, `IntConst`, `FloatConst`, `StringConst`, `Vec3Const`. Run `grep -rn "StockEvaluators\.\(BoolConst\|IntConst\|FloatConst\|StringConst\|Vec3Const\)" src` — every match must be gone.

- [ ] **Step 9: Remove obsolete public Composables from NodeConfigContent**

Public `BoolConst` / `IntConst` / `FloatConst` / `StringConst` Composables — already turned into private helpers in Step 5. Make sure no public references remain. Run `grep -rn "NodeConfigContent\.\(BoolConst\|IntConst\|FloatConst\|StringConst\)" src` — must be empty.

- [ ] **Step 10: Update callsites**

Run `grep -rn "BOOL_CONST\|INT_CONST\|FLOAT_CONST\|STRING_CONST\|VEC3_CONST" src` — for each match, rewrite the line. Setup pattern:

```kotlin
// OLD
val a = StockNodeTypes.BOOL_CONST.newInstance().also { it.config.putBoolean("value", true) }
// NEW
val a = StockNodeTypes.CONSTANT.newInstance().also {
    it.config.putString("type", "BOOL"); it.config.putBoolean("bool", true)
}
```

The new constant's `newInstance` already sets a default config (BOOL, all-zero slots), so test code only needs to overwrite the slots it cares about. `STRING_CONST` callers swap to `"type": "STRING"` + `"string": ...`. `INT_CONST` callers swap to `"type": "INT"` + `"int": ...`.

Known callsite files: `NodeEditorScreen.kt::seedIfEmpty`, `DemoScreen.kt`, `GraphEvaluatorTest.kt`, `StockNodeTypesTest.kt`.

- [ ] **Step 11: Fix `StockNodeTypesTest`**

This test counts registered types and references specific ones. Update its expected total: remove 5, add 1, net `-4`. Find and adjust the count assertion (last verified count was 48 after FROM_REDSTONE).

Also if `StockNodeTypesTest` instantiates `INT_CONST` directly, swap to `CONSTANT` with `"type": "INT"`.

- [ ] **Step 12: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All tests green. If anything fails, fix it before committing.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(graph): consolidate 5 constant nodes into single configurable Constant

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2: LogicGate node

**Replaces:** `AND`, `OR`, `NOT`, `XOR`, `NAND`, `NOR`, `XNOR`.

**Files:** same set as Phase 1 plus `EditorStateLogicGateTest`, `StockEvaluatorsLogicGateTest`. Plus the helper `boolBinary(...)` in `StockNodeTypes.kt` becomes orphaned — delete it after the last caller is gone.

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
// StockEvaluatorsLogicGateTest.kt
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsLogicGateTest {

    private fun cfg(op: String) = CompoundTag().apply { putString("op", op) }
    private fun ab(a: Boolean, b: Boolean) = mapOf(
        "a" to PinValue.Bool(a),
        "b" to PinValue.Bool(b),
    )
    private fun unary(v: Boolean) = mapOf("in" to PinValue.Bool(v))

    @Test fun andTrue()   = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("AND"),  ab(true, true))["out"])
    @Test fun orFalse()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("OR"),   ab(false, false))["out"])
    @Test fun notTrue()   = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("NOT"),  unary(true))["out"])
    @Test fun xorMixed()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("XOR"),  ab(true, false))["out"])
    @Test fun nandTrue()  = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("NAND"), ab(true, true))["out"])
    @Test fun norFalse()  = assertEquals(PinValue.Bool(true),  StockEvaluators.LogicGate(cfg("NOR"),  ab(false, false))["out"])
    @Test fun xnorMixed() = assertEquals(PinValue.Bool(false), StockEvaluators.LogicGate(cfg("XNOR"), ab(true, false))["out"])
}
```

- [ ] **Step 2: Verify tests fail, implement evaluator**

`StockEvaluators.LogicGate` — body from the spec. Use the helper `boolIn(inputs, key)` already present.

- [ ] **Step 3: Add EditorState mutator + test**

`EditorStateLogicGateTest.kt`:

```kotlin
@Test fun changeLogicGateOpRebuildsInputsAndDisconnects() {
    val graph = NodeGraph()
    val gate = StockNodeTypes.LOGIC_GATE.newInstance(CanvasPos.Zero) // default AND
    val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero) // bool
    graph.add(gate); graph.add(source)
    graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(gate.id, "a")))
    val editor = EditorState(graph, BlockPos.ZERO)
    editor.changeLogicGateOp(gate.id, "NOT")
    val updated = editor.nodeFlow(gate.id)!!.value
    assertEquals(1, updated.inputs.size)
    assertEquals("in", updated.inputs.first().id)
    assertTrue(editor.edges.value.isEmpty())
}
```

In `EditorState.kt`:

```kotlin
fun changeLogicGateOp(id: NodeId, newOp: String) {
    updateNode(id) { n ->
        val inputs = if (newOp == "NOT") {
            listOf(Pin("in", "In", PinType.BOOL))
        } else {
            listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL))
        }
        val newConfig = n.config.copy().apply { putString("op", newOp) }
        n.copy(inputs = inputs, config = newConfig)
    }
    disconnectAllEdges(id)
}
```

(Add the necessary imports for `Pin`, `PinType`, `NodeId` at the top of `EditorState.kt` if not already there — check first.)

- [ ] **Step 4: Add UI Composable**

In `NodeConfigContent.kt`:

```kotlin
val LogicGate: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var op by remember(node.id) {
        mutableStateOf(node.config.getString("op").ifEmpty { "AND" })
    }
    LabeledRow("Op") {
        Select(
            options = LOGIC_OPS,
            selected = op,
            onSelect = { next ->
                op = next
                editor?.changeLogicGateOp(node.id, next)
            },
            label = { it },
        )
    }
}

private val LOGIC_OPS = listOf("AND", "OR", "NOT", "XOR", "NAND", "NOR", "XNOR")
```

- [ ] **Step 5: Register LOGIC_GATE; delete old AND/OR/NOT/XOR/NAND/NOR/XNOR + their evaluators + `boolBinary` helper**

In `StockNodeTypes.kt`:

```kotlin
val LOGIC_GATE = nodeType(
    id = "logic_gate",
    displayName = "Logic Gate",
    category = NodeCategory.LOGIC,
    inputs = listOf(Pin("a", "A", PinType.BOOL), Pin("b", "B", PinType.BOOL)),
    outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    defaultConfig = { CompoundTag().apply { putString("op", "AND") } },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.LogicGate,
    evaluate = StockEvaluators.LogicGate,
)
```

Delete the 7 old `val` declarations + the `boolBinary(...)` helper (it becomes orphaned). In `registerAll()`, replace the `// Logic` group with `LOGIC_GATE`.

In `StockEvaluators.kt`, delete: `And`, `Or`, `Not`, `Xor`, `Nand`, `Nor`, `Xnor`.

- [ ] **Step 6: Update callsites**

`grep -rn "StockNodeTypes\.\(AND\|OR\|NOT\|XOR\|NAND\|NOR\|XNOR\)\b" src` — update all matches to use `LOGIC_GATE` with the appropriate `op`:

```kotlin
// OLD
val and = StockNodeTypes.AND.newInstance()
// NEW
val and = StockNodeTypes.LOGIC_GATE.newInstance() // default op=AND
```

For NOT specifically, the `newInstance()` returns a 2-input default; tests will need an explicit mutator-style swap. Simplest: instantiate, then directly mutate `config.op` to "NOT" and rebuild `inputs` to `[Pin("in", "In", BOOL)]` inline. Since these are test/seed callers that don't go through EditorState, the manual fixup is fine.

Update count assertion in `StockNodeTypesTest` (-7 +1 = -6).

- [ ] **Step 7: Build + commit**

`./gradlew build` → green. Commit `refactor(graph): consolidate 7 boolean gates into single configurable LogicGate`.

---

## Phase 3: Math node

**Replaces:** `ADD_INT`, `ADD_FLOAT`, `SUB_INT`, `SUB_FLOAT`, `MUL_INT`, `MUL_FLOAT`, `DIV_INT`, `DIV_FLOAT`, `MOD_INT`.

**Note:** `ADD_VEC3`, `NEG_FLOAT`, `ABS_FLOAT`, `MIN_FLOAT`, `MAX_FLOAT`, `CLAMP_FLOAT` are **out of scope** — they survive untouched. `intBinary(...)` and `floatBinary(...)` helpers in `StockNodeTypes.kt` may be needed by surviving callers (e.g., MIN_FLOAT) — keep them unless every caller is gone.

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
// StockEvaluatorsMathTest.kt — sample
@Test fun intAdd() = assertEquals(
    PinValue.Int(7),
    StockEvaluators.Math(
        cfg { putString("op", "ADD"); putString("type", "INT") },
        mapOf("a" to PinValue.Int(3), "b" to PinValue.Int(4)),
    )["out"],
)
@Test fun floatSub() = assertEquals(
    PinValue.Float(1.5f),
    StockEvaluators.Math(
        cfg { putString("op", "SUB"); putString("type", "FLOAT") },
        mapOf("a" to PinValue.Float(2.5f), "b" to PinValue.Float(1.0f)),
    )["out"],
)
@Test fun intDivByZeroReturnsZero() = assertEquals(
    PinValue.Int(0),
    StockEvaluators.Math(
        cfg { putString("op", "DIV"); putString("type", "INT") },
        mapOf("a" to PinValue.Int(5), "b" to PinValue.Int(0)),
    )["out"],
)
@Test fun floatDivByZeroReturnsZero() = assertEquals(
    PinValue.Float(0f),
    StockEvaluators.Math(
        cfg { putString("op", "DIV"); putString("type", "FLOAT") },
        mapOf("a" to PinValue.Float(5f), "b" to PinValue.Float(0f)),
    )["out"],
)
@Test fun intModByZeroReturnsZero() = assertEquals(
    PinValue.Int(0),
    StockEvaluators.Math(
        cfg { putString("op", "MOD"); putString("type", "INT") },
        mapOf("a" to PinValue.Int(5), "b" to PinValue.Int(0)),
    )["out"],
)
// + intSub, intMul, intDiv, intMod, floatAdd, floatMul, floatDiv: one assertion each.
```

(Write all 11 tests. Mirror layout above.)

- [ ] **Step 2: Implement evaluator**

```kotlin
val Math: NodeEvaluator = { config, inputs ->
    val op = config.getString("op").ifEmpty { "ADD" }
    val type = config.getString("type").ifEmpty { "INT" }
    val out: PinValue = if (type == "FLOAT") {
        val a = floatIn(inputs, "a"); val b = floatIn(inputs, "b")
        PinValue.Float(when (op) {
            "SUB" -> a - b
            "MUL" -> a * b
            "DIV" -> if (b == 0f) 0f else a / b
            else -> a + b // "ADD"
        })
    } else {
        val a = intIn(inputs, "a"); val b = intIn(inputs, "b")
        PinValue.Int(when (op) {
            "SUB" -> a - b
            "MUL" -> a * b
            "DIV" -> if (b == 0) 0 else a / b
            "MOD" -> if (b == 0) 0 else a % b
            else -> a + b
        })
    }
    mapOf("out" to out)
}
```

- [ ] **Step 3: Mutator + test**

```kotlin
fun changeMathConfig(id: NodeId, newOp: String, newType: PinType) {
    updateNode(id) { n ->
        val inputs = listOf(Pin("a", "A", newType), Pin("b", "B", newType))
        val outputs = listOf(Pin("out", "Out", newType))
        val newConfig = n.config.copy().apply {
            putString("op", newOp)
            putString("type", newType.name)
        }
        n.copy(inputs = inputs, outputs = outputs, config = newConfig)
    }
    disconnectAllEdges(id)
}
```

Test verifies pin types switch to FLOAT after `changeMathConfig(id, "SUB", PinType.FLOAT)`.

- [ ] **Step 4: UI Composable**

```kotlin
val Math: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var type by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.INT.name }))
    }
    var op by remember(node.id) {
        mutableStateOf(node.config.getString("op").ifEmpty { "ADD" })
    }
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        LabeledRow("Type") {
            Select(
                options = MATH_TYPES,
                selected = type,
                onSelect = { next ->
                    type = next
                    // MOD is INT-only; coerce op if switching to FLOAT.
                    val coercedOp = if (next == PinType.FLOAT && op == "MOD") "ADD" else op
                    op = coercedOp
                    editor?.changeMathConfig(node.id, coercedOp, next)
                },
                label = { it.name.lowercase() },
            )
        }
        LabeledRow("Op") {
            Select(
                options = opsForMathType(type),
                selected = op,
                onSelect = { next ->
                    op = next
                    editor?.changeMathConfig(node.id, next, type)
                },
                label = { it },
            )
        }
    }
}

private val MATH_TYPES = listOf(PinType.INT, PinType.FLOAT)
private fun opsForMathType(t: PinType) = when (t) {
    PinType.INT -> listOf("ADD", "SUB", "MUL", "DIV", "MOD")
    else -> listOf("ADD", "SUB", "MUL", "DIV")
}
```

- [ ] **Step 5: Register MATH; delete 9 old; delete their evaluators**

```kotlin
val MATH = nodeType(
    id = "math",
    displayName = "Math",
    category = NodeCategory.MATH,
    inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
    outputs = listOf(Pin("out", "Out", PinType.INT)),
    defaultConfig = {
        CompoundTag().apply {
            putString("op", "ADD")
            putString("type", PinType.INT.name)
        }
    },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Math,
    evaluate = StockEvaluators.Math,
)
```

Delete `ADD_INT`, `ADD_FLOAT`, `SUB_INT`, `SUB_FLOAT`, `MUL_INT`, `MUL_FLOAT`, `DIV_INT`, `DIV_FLOAT`, `MOD_INT` declarations. In `registerAll()` `// Math` group: keep `ADD_VEC3`, `NEG_FLOAT`, `ABS_FLOAT`, `MIN_FLOAT`, `MAX_FLOAT`, `CLAMP_FLOAT`, swap the 9 deleted IDs for `MATH`.

In `StockEvaluators.kt`, delete `AddInt`, `AddFloat`, `SubInt`, `SubFloat`, `MulInt`, `MulFloat`, `DivInt`, `DivFloat`, `ModInt`.

- [ ] **Step 6: Update callsites**

`grep -rn "StockNodeTypes\.\(ADD_INT\|ADD_FLOAT\|SUB_INT\|SUB_FLOAT\|MUL_INT\|MUL_FLOAT\|DIV_INT\|DIV_FLOAT\|MOD_INT\)\b" src` — update each. Pattern:

```kotlin
// OLD
val add = StockNodeTypes.ADD_INT.newInstance()
// NEW (default config is op=ADD, type=INT)
val add = StockNodeTypes.MATH.newInstance()
```

For non-ADD or non-INT swaps, also write the config:

```kotlin
val sub = StockNodeTypes.MATH.newInstance().also {
    it.config.putString("op", "SUB"); it.config.putString("type", "INT")
    // also rebuild pins if test inspects them — but evaluator only cares about config
}
```

If a test inspects `pin.type` after `.newInstance()` for old `ADD_FLOAT` (expected FLOAT), this will fail because default is INT. In that case, switch the test to use FLOAT explicitly *and* swap pin types manually (or run through EditorState). Simplest: avoid pin-type assertions in tests that just want to verify math behavior — they should only check the evaluator output.

Update `StockNodeTypesTest` count (-9 +1 = -8).

- [ ] **Step 7: Build + commit**

`./gradlew build` → green. Commit `refactor(graph): consolidate 9 binary math nodes into single configurable Math`.

---

## Phase 4: Compare node

**Replaces:** `COMPARE_INT`, `COMPARE_FLOAT`.

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
// StockEvaluatorsCompareTest.kt
@Test fun intGt() = assertOutputs(
    StockEvaluators.Compare(
        cfg { putString("type", "INT") },
        mapOf("a" to PinValue.Int(5), "b" to PinValue.Int(3)),
    ),
    gt = true, eq = false, lt = false,
)
@Test fun intEq() = assertOutputs(
    StockEvaluators.Compare(
        cfg { putString("type", "INT") },
        mapOf("a" to PinValue.Int(3), "b" to PinValue.Int(3)),
    ),
    gt = false, eq = true, lt = false,
)
@Test fun intLt() = assertOutputs(
    StockEvaluators.Compare(
        cfg { putString("type", "INT") },
        mapOf("a" to PinValue.Int(1), "b" to PinValue.Int(2)),
    ),
    gt = false, eq = false, lt = true,
)
@Test fun floatLt() = assertOutputs(
    StockEvaluators.Compare(
        cfg { putString("type", "FLOAT") },
        mapOf("a" to PinValue.Float(1.0f), "b" to PinValue.Float(2.0f)),
    ),
    gt = false, eq = false, lt = true,
)

private fun assertOutputs(out: Map<String, PinValue>, gt: Boolean, eq: Boolean, lt: Boolean) {
    assertEquals(PinValue.Bool(gt), out["gt"])
    assertEquals(PinValue.Bool(eq), out["eq"])
    assertEquals(PinValue.Bool(lt), out["lt"])
}
```

- [ ] **Step 2: Implement evaluator**

```kotlin
val Compare: NodeEvaluator = { config, inputs ->
    val type = config.getString("type").ifEmpty { "INT" }
    val gt: Boolean; val eq: Boolean; val lt: Boolean
    if (type == "FLOAT") {
        val a = floatIn(inputs, "a"); val b = floatIn(inputs, "b")
        gt = a > b; eq = a == b; lt = a < b
    } else {
        val a = intIn(inputs, "a"); val b = intIn(inputs, "b")
        gt = a > b; eq = a == b; lt = a < b
    }
    mapOf(
        "gt" to PinValue.Bool(gt),
        "eq" to PinValue.Bool(eq),
        "lt" to PinValue.Bool(lt),
    )
}
```

- [ ] **Step 3: Mutator + test**

```kotlin
fun changeCompareType(id: NodeId, newType: PinType) {
    updateNode(id) { n ->
        val inputs = listOf(Pin("a", "A", newType), Pin("b", "B", newType))
        val newConfig = n.config.copy().apply { putString("type", newType.name) }
        n.copy(inputs = inputs, config = newConfig)
    }
    disconnectAllEdges(id)
}
```

- [ ] **Step 4: UI Composable**

```kotlin
val Compare: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var type by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("type").ifEmpty { PinType.INT.name }))
    }
    LabeledRow("Type") {
        Select(
            options = MATH_TYPES,  // reuse from Phase 3
            selected = type,
            onSelect = { next ->
                type = next
                editor?.changeCompareType(node.id, next)
            },
            label = { it.name.lowercase() },
        )
    }
}
```

- [ ] **Step 5: Register COMPARE; delete the 2 old + their evaluators**

```kotlin
val COMPARE = nodeType(
    id = "compare",
    displayName = "Compare",
    category = NodeCategory.MATH,
    inputs = listOf(Pin("a", "A", PinType.INT), Pin("b", "B", PinType.INT)),
    outputs = listOf(
        Pin("gt", "A > B", PinType.BOOL),
        Pin("eq", "A = B", PinType.BOOL),
        Pin("lt", "A < B", PinType.BOOL),
    ),
    defaultConfig = { CompoundTag().apply { putString("type", PinType.INT.name) } },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Compare,
    evaluate = StockEvaluators.Compare,
)
```

Delete `COMPARE_INT`, `COMPARE_FLOAT` from `StockNodeTypes.kt`. Delete `CompareInt`, `CompareFloat` from `StockEvaluators.kt`. Update `registerAll()`.

- [ ] **Step 6: Update callsites**

`grep -rn "StockNodeTypes\.\(COMPARE_INT\|COMPARE_FLOAT\)\b" src` — replace. Pattern:

```kotlin
// OLD
val cmp = StockNodeTypes.COMPARE_INT.newInstance()
// NEW
val cmp = StockNodeTypes.COMPARE.newInstance() // default INT
```

`StockNodeTypesTest` — count assertion -2 +1 = -1; also update any line referencing `COMPARE_INT.outputs.size` etc. to use `COMPARE`.

- [ ] **Step 7: Build + commit**

`./gradlew build` → green. Commit `refactor(graph): consolidate 2 compare nodes into single configurable Compare`.

---

## Phase 5: Convert node

**Replaces:** `INT_TO_FLOAT`, `FLOAT_TO_INT`, `BOOL_TO_INT`, `INT_TO_BOOL`.

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
// StockEvaluatorsConvertTest.kt
@Test fun intToFloat() = assertEquals(
    PinValue.Float(5f),
    StockEvaluators.Convert(
        cfg { putString("sourceType", "INT"); putString("targetType", "FLOAT") },
        mapOf("in" to PinValue.Int(5)),
    )["out"],
)
@Test fun floatToInt() = assertEquals(
    PinValue.Int(3),
    StockEvaluators.Convert(
        cfg { putString("sourceType", "FLOAT"); putString("targetType", "INT") },
        mapOf("in" to PinValue.Float(3.7f)),
    )["out"],
)
@Test fun boolToInt() = assertEquals(
    PinValue.Int(1),
    StockEvaluators.Convert(
        cfg { putString("sourceType", "BOOL"); putString("targetType", "INT") },
        mapOf("in" to PinValue.Bool(true)),
    )["out"],
)
@Test fun intToBool() = assertEquals(
    PinValue.Bool(true),
    StockEvaluators.Convert(
        cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
        mapOf("in" to PinValue.Int(7)),
    )["out"],
)
@Test fun intToBoolZeroIsFalse() = assertEquals(
    PinValue.Bool(false),
    StockEvaluators.Convert(
        cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
        mapOf("in" to PinValue.Int(0)),
    )["out"],
)
```

Match the cast semantics of the originals — check `StockEvaluators.IntToFloat`, `FloatToInt`, `BoolToInt`, `IntToBool` before deleting them so the new impl matches. (`IntToBool` likely is `x != 0`; `BoolToInt` is `1 / 0`; `FloatToInt` is `toInt()` (truncates); `IntToFloat` is `toFloat()`.)

- [ ] **Step 2: Implement evaluator**

```kotlin
val Convert: NodeEvaluator = { config, inputs ->
    val src = config.getString("sourceType").ifEmpty { "INT" }
    val tgt = config.getString("targetType").ifEmpty { "FLOAT" }
    val out: PinValue = when (src to tgt) {
        "INT" to "FLOAT" -> PinValue.Float(intIn(inputs, "in").toFloat())
        "FLOAT" to "INT" -> PinValue.Int(floatIn(inputs, "in").toInt())
        "BOOL" to "INT" -> PinValue.Int(if (boolIn(inputs, "in")) 1 else 0)
        "INT" to "BOOL" -> PinValue.Bool(intIn(inputs, "in") != 0)
        else -> PinValue.default(PinType.fromName(tgt))
    }
    mapOf("out" to out)
}
```

- [ ] **Step 3: Mutator + test**

```kotlin
fun changeConvertTypes(id: NodeId, source: PinType, target: PinType) {
    updateNode(id) { n ->
        val inputs = listOf(Pin("in", "In", source))
        val outputs = listOf(Pin("out", "Out", target))
        val newConfig = n.config.copy().apply {
            putString("sourceType", source.name)
            putString("targetType", target.name)
        }
        n.copy(inputs = inputs, outputs = outputs, config = newConfig)
    }
    disconnectAllEdges(id)
}
```

- [ ] **Step 4: UI Composable**

```kotlin
val Convert: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var source by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("sourceType").ifEmpty { PinType.INT.name }))
    }
    var target by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("targetType").ifEmpty { PinType.FLOAT.name }))
    }
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        LabeledRow("From") {
            Select(
                options = CONVERT_SOURCES,
                selected = source,
                onSelect = { next ->
                    source = next
                    val newTarget = validTargetsFor(next).firstOrNull { it == target }
                        ?: validTargetsFor(next).first()
                    target = newTarget
                    editor?.changeConvertTypes(node.id, next, newTarget)
                },
                label = { it.name.lowercase() },
            )
        }
        LabeledRow("To") {
            Select(
                options = validTargetsFor(source),
                selected = target,
                onSelect = { next ->
                    target = next
                    editor?.changeConvertTypes(node.id, source, next)
                },
                label = { it.name.lowercase() },
            )
        }
    }
}

private val CONVERT_SOURCES = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)
private fun validTargetsFor(src: PinType) = when (src) {
    PinType.INT -> listOf(PinType.FLOAT, PinType.BOOL)
    PinType.FLOAT -> listOf(PinType.INT)
    PinType.BOOL -> listOf(PinType.INT)
    else -> emptyList()
}
```

- [ ] **Step 5: Register CONVERT; delete the 4 old + their evaluators**

```kotlin
val CONVERT = nodeType(
    id = "convert",
    displayName = "Convert",
    category = NodeCategory.CONVERSION,
    inputs = listOf(Pin("in", "In", PinType.INT)),
    outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
    defaultConfig = {
        CompoundTag().apply {
            putString("sourceType", PinType.INT.name)
            putString("targetType", PinType.FLOAT.name)
        }
    },
    configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.Convert,
    evaluate = StockEvaluators.Convert,
)
```

Delete `INT_TO_FLOAT`, `FLOAT_TO_INT`, `BOOL_TO_INT`, `INT_TO_BOOL`. In `registerAll()` `// Conversion` group, replace the 4 IDs with `CONVERT`. Keep `CONVERT_TO_REDSTONE` and `FROM_REDSTONE`.

In `StockEvaluators.kt`, delete `IntToFloat`, `FloatToInt`, `BoolToInt`, `IntToBool`.

- [ ] **Step 6: Update callsites**

`grep -rn "StockNodeTypes\.\(INT_TO_FLOAT\|FLOAT_TO_INT\|BOOL_TO_INT\|INT_TO_BOOL\)\b" src` — replace per pattern:

```kotlin
// OLD
val c = StockNodeTypes.BOOL_TO_INT.newInstance()
// NEW
val c = StockNodeTypes.CONVERT.newInstance().also {
    it.config.putString("sourceType", "BOOL")
    it.config.putString("targetType", "INT")
}
```

`StockNodeTypesTest` count: -4 +1 = -3.

- [ ] **Step 7: Build + commit**

`./gradlew build` → green. Commit `refactor(graph): consolidate 4 type converters into single configurable Convert`.

---

## Phase 6: Final cleanup + verification

- [ ] **Step 1: Codec roundtrip coverage**

Extend (or add) tests in `CodecRoundTripTest` (or its equivalent — find via `grep -rn "CodecRoundTrip\|roundtrip" src/test`) to roundtrip one instance of each consolidated node with non-default config:

- `CONSTANT` with `type=STRING`, `string="hello"`.
- `LOGIC_GATE` with `op="XOR"`.
- `MATH` with `op="DIV"`, `type="FLOAT"`.
- `COMPARE` with `type="FLOAT"`.
- `CONVERT` with `sourceType="BOOL"`, `targetType="INT"`.

For each: serialize via `Node.CODEC`, deserialize, assert equality (or assert key fields if the existing test pattern asserts piecewise).

- [ ] **Step 2: Final sweep — confirm no orphans**

Run these greps. Each must return empty:

```bash
grep -rn "BOOL_CONST\|INT_CONST\|FLOAT_CONST\|STRING_CONST\|VEC3_CONST" src
grep -rn "StockNodeTypes\.\(AND\|OR\|NOT\|XOR\|NAND\|NOR\|XNOR\)\b" src
grep -rn "StockNodeTypes\.\(ADD_INT\|ADD_FLOAT\|SUB_INT\|SUB_FLOAT\|MUL_INT\|MUL_FLOAT\|DIV_INT\|DIV_FLOAT\|MOD_INT\)\b" src
grep -rn "StockNodeTypes\.\(COMPARE_INT\|COMPARE_FLOAT\)\b" src
grep -rn "StockNodeTypes\.\(INT_TO_FLOAT\|FLOAT_TO_INT\|BOOL_TO_INT\|INT_TO_BOOL\)\b" src
grep -rn "StockEvaluators\.\(BoolConst\|IntConst\|FloatConst\|StringConst\|Vec3Const\|And\|Or\|Not\|Xor\|Nand\|Nor\|Xnor\|AddInt\|AddFloat\|SubInt\|SubFloat\|MulInt\|MulFloat\|DivInt\|DivFloat\|ModInt\|CompareInt\|CompareFloat\|IntToFloat\|FloatToInt\|BoolToInt\|IntToBool\)\b" src
```

Fix any stragglers.

- [ ] **Step 3: Final full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Total registered types is now 48 (pre-consolidation) − 27 (deleted) + 5 (new) = **26**. Update `StockNodeTypesTest` count assertion if any prior phase missed it.

- [ ] **Step 4: Commit (if there are residual changes)**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(graph): finalize node consolidation — codec coverage + cleanup

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

If everything was already clean after Phase 5 and only codec test additions remain, fold them into Phase 6 commit.

- [ ] **Step 5: Hand-off note**

In-game smoke:
- Add `Constant` → switch type BOOL → INT → STRING → VEC3 → output pin rebuilds, value field changes shape.
- Add `Logic Gate` → switch op AND → NOT → second input pin disappears.
- Add `Math` → switch type INT → FLOAT → MOD option disappears from Op select.
- Add `Compare` → switch type INT → FLOAT → 3 outputs stay BOOL.
- Add `Convert` → switch From=FLOAT → To options become INT only.
