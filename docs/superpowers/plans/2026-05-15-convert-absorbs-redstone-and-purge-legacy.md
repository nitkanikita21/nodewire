# Convert v2 + Legacy Math Purge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Stay on master.

**Goal:**
1. Expand `CONVERT` to absorb the deleted `CONVERT_TO_REDSTONE` + `FROM_REDSTONE` nodes (adds REDSTONE as valid source/target with per-pair mode config).
2. Delete legacy single-type math nodes: `ADD_VEC3`, `NEG_FLOAT`, `ABS_FLOAT`, `MIN_FLOAT`, `MAX_FLOAT`, `CLAMP_FLOAT`.

**Spec:** `docs/superpowers/specs/2026-05-15-convert-absorbs-redstone-and-purge-legacy.md`

**Tech:** Kotlin 2.0.20, compose-runtime 1.7.0, JUnit 5.

**End state:** Registry count = 18 (was 26).

---

### Task 1: Expand `StockEvaluators.Convert` to handle REDSTONE pairs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConvertTest.kt`

- [ ] **Step 1: Write failing tests for every REDSTONE pair × mode combination**

Append to the existing `StockEvaluatorsConvertTest` file. Use the same `cfg { }` helper that's already there:

```kotlin
@Test fun intToRedstoneClamp() = assertEquals(
    PinValue.Redstone(15),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","INT"); putString("targetType","REDSTONE")
            putString("mode","clamp")
        },
        mapOf("in" to PinValue.Int(20)),
    )["out"],
)
@Test fun intToRedstoneModulo() = assertEquals(
    PinValue.Redstone(4),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","INT"); putString("targetType","REDSTONE")
            putString("mode","modulo")
        },
        mapOf("in" to PinValue.Int(20)),
    )["out"],
)
@Test fun intToRedstoneThreshold() = assertEquals(
    PinValue.Redstone(15),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","INT"); putString("targetType","REDSTONE")
            putString("mode","threshold"); putInt("threshold",5)
        },
        mapOf("in" to PinValue.Int(7)),
    )["out"],
)
@Test fun intToRedstoneScaled() = assertEquals(
    PinValue.Redstone(15),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","INT"); putString("targetType","REDSTONE")
            putString("mode","scaled"); putInt("min",0); putInt("max",100)
        },
        mapOf("in" to PinValue.Int(100)),
    )["out"],
)
@Test fun floatToRedstoneThreshold() = assertEquals(
    PinValue.Redstone(0),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","FLOAT"); putString("targetType","REDSTONE")
            putString("mode","threshold"); putFloat("thresholdF",1f)
        },
        mapOf("in" to PinValue.Float(0.5f)),
    )["out"],
)
@Test fun floatToRedstoneScaled() = assertEquals(
    PinValue.Redstone(15),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","FLOAT"); putString("targetType","REDSTONE")
            putString("mode","scaled"); putFloat("minF",0f); putFloat("maxF",1f)
        },
        mapOf("in" to PinValue.Float(1f)),
    )["out"],
)
@Test fun boolToRedstoneHi() = assertEquals(
    PinValue.Redstone(15),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","BOOL"); putString("targetType","REDSTONE")
            putString("mode","hi")
        },
        mapOf("in" to PinValue.Bool(true)),
    )["out"],
)
@Test fun boolToRedstoneLevel() = assertEquals(
    PinValue.Redstone(7),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","BOOL"); putString("targetType","REDSTONE")
            putString("mode","level"); putInt("level",7)
        },
        mapOf("in" to PinValue.Bool(true)),
    )["out"],
)
@Test fun redstoneToIntRaw() = assertEquals(
    PinValue.Int(7),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","INT")
            putString("mode","raw")
        },
        mapOf("in" to PinValue.Redstone(7)),
    )["out"],
)
@Test fun redstoneToIntScaled() = assertEquals(
    PinValue.Int(100),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","INT")
            putString("mode","scaled"); putInt("min",0); putInt("max",100)
        },
        mapOf("in" to PinValue.Redstone(15)),
    )["out"],
)
@Test fun redstoneToFloatNormalized() = assertEquals(
    PinValue.Float(1f),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
            putString("mode","normalized")
        },
        mapOf("in" to PinValue.Redstone(15)),
    )["out"],
)
@Test fun redstoneToFloatRaw() = assertEquals(
    PinValue.Float(7f),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
            putString("mode","raw")
        },
        mapOf("in" to PinValue.Redstone(7)),
    )["out"],
)
@Test fun redstoneToFloatScaled() = assertEquals(
    PinValue.Float(1f),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
            putString("mode","scaled"); putFloat("minF",-1f); putFloat("maxF",1f)
        },
        mapOf("in" to PinValue.Redstone(15)),
    )["out"],
)
@Test fun redstoneToBoolAny() = assertEquals(
    PinValue.Bool(true),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","BOOL")
            putString("mode","any")
        },
        mapOf("in" to PinValue.Redstone(1)),
    )["out"],
)
@Test fun redstoneToBoolThreshold() = assertEquals(
    PinValue.Bool(false),
    StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","BOOL")
            putString("mode","threshold"); putInt("threshold",8)
        },
        mapOf("in" to PinValue.Redstone(7)),
    )["out"],
)
```

Verify `PinValue.Redstone` is the actual class name (the FromRedstone phase used it). Adjust if needed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsConvertTest"`
Expected: 15 new test failures.

- [ ] **Step 3: Extend `StockEvaluators.Convert` to handle all 10 valid pairs**

Replace the existing `Convert: NodeEvaluator` with one that dispatches on `(sourceType, targetType)`. The four cast pairs keep their semantics; the six mode-bearing pairs inline the logic from the deleted `ConvertToRedstone` and `FromRedstone` evaluators.

Reference: `git show 4d62d29:src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` shows the FromRedstone body. `git log --all --diff-filter=D -- '**/StockEvaluators.kt' | head` may help find the ConvertToRedstone deletion (it was deleted in Phase 5 of the prior consolidation; reconstruct from the FromRedstone phase's referenced semantics in the consolidation spec).

Concrete body:

```kotlin
val Convert: NodeEvaluator = { config, inputs ->
    val src = config.getString("sourceType").ifEmpty { "INT" }
    val tgt = config.getString("targetType").ifEmpty { "FLOAT" }
    val mode = config.getString("mode")
    val out: PinValue = when (src to tgt) {
        "INT" to "FLOAT" -> PinValue.Float(intIn(inputs, "in").toFloat())
        "FLOAT" to "INT" -> PinValue.Int(floatIn(inputs, "in").toInt())
        "BOOL" to "INT" -> PinValue.Int(if (boolIn(inputs, "in")) 1 else 0)
        "INT" to "BOOL" -> PinValue.Bool(intIn(inputs, "in") != 0)

        "INT" to "REDSTONE" -> {
            val x = intIn(inputs, "in")
            val v = when (mode) {
                "modulo" -> ((x % 16) + 16) % 16
                "threshold" -> if (x >= config.getInt("threshold")) 15 else 0
                "scaled" -> {
                    val lo = config.getInt("min"); val hi = config.getInt("max")
                    if (hi == lo) 0 else (((x - lo).toFloat() / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                }
                else -> x.coerceIn(0, 15) // "clamp"
            }
            PinValue.Redstone(v)
        }
        "FLOAT" to "REDSTONE" -> {
            val x = floatIn(inputs, "in")
            val v = when (mode) {
                "threshold" -> if (x >= config.getFloat("thresholdF")) 15 else 0
                else -> { // "scaled"
                    val lo = config.getFloat("minF"); val hi = config.getFloat("maxF")
                    if (hi == lo) 0 else (((x - lo) / (hi - lo)) * 15f).toInt().coerceIn(0, 15)
                }
            }
            PinValue.Redstone(v)
        }
        "BOOL" to "REDSTONE" -> {
            val x = boolIn(inputs, "in")
            val v = when (mode) {
                "level" -> if (x) config.getInt("level").coerceIn(0, 15) else 0
                else -> if (x) 15 else 0 // "hi"
            }
            PinValue.Redstone(v)
        }
        "REDSTONE" to "INT" -> {
            val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
            val v = when (mode) {
                "scaled" -> {
                    val lo = config.getInt("min"); val hi = config.getInt("max")
                    if (hi == lo) lo else lo + ((signal.toFloat() / 15f) * (hi - lo)).toInt()
                }
                else -> signal // "raw"
            }
            PinValue.Int(v)
        }
        "REDSTONE" to "FLOAT" -> {
            val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
            val v = when (mode) {
                "raw" -> signal.toFloat()
                "scaled" -> {
                    val lo = config.getFloat("minF"); val hi = config.getFloat("maxF")
                    if (hi == lo) lo else lo + (signal / 15f) * (hi - lo)
                }
                else -> signal / 15f // "normalized"
            }
            PinValue.Float(v)
        }
        "REDSTONE" to "BOOL" -> {
            val signal = ((inputs["in"] as? PinValue.Redstone)?.value ?: 0).coerceIn(0, 15)
            val v = when (mode) {
                "threshold" -> signal >= config.getInt("threshold")
                else -> signal > 0 // "any"
            }
            PinValue.Bool(v)
        }
        else -> PinValue.default(PinType.fromName(tgt))
    }
    mapOf("out" to out)
}
```

Adjust `inputs["in"] as? PinValue.Redstone` if `PinValue.Redstone` isn't the actual class name — match what the rest of the project uses.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.StockEvaluatorsConvertTest"`
Expected: All tests green (15 new + the existing 5 from Phase 5).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsConvertTest.kt
git commit -m "$(cat <<'EOF'
feat(graph): Convert evaluator handles REDSTONE source/target with modes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Update EditorState mutator + add `changeConvertMode`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateConvertTest.kt`

- [ ] **Step 1: Write failing tests**

Append to the existing `EditorStateConvertTest`:

```kotlin
@Test
fun changeConvertTypesToRedstonePairWritesDefaultMode() {
    val graph = NodeGraph()
    val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero)
    graph.add(n)
    val editor = EditorState(graph, BlockPos.ZERO)
    editor.changeConvertTypes(n.id, PinType.INT, PinType.REDSTONE)
    val updated = editor.nodeFlow(n.id)!!.value
    assertEquals(PinType.INT, updated.inputs.first().type)
    assertEquals(PinType.REDSTONE, updated.outputs.first().type)
    assertEquals("clamp", updated.config.getString("mode"))
}

@Test
fun changeConvertTypesBackToCastPairClearsMode() {
    val graph = NodeGraph()
    val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero).also {
        it.config.putString("sourceType","INT"); it.config.putString("targetType","REDSTONE")
        it.config.putString("mode","scaled")
    }
    graph.add(n)
    val editor = EditorState(graph, BlockPos.ZERO)
    editor.changeConvertTypes(n.id, PinType.INT, PinType.FLOAT)
    val updated = editor.nodeFlow(n.id)!!.value
    assertEquals("", updated.config.getString("mode"))
}

@Test
fun changeConvertModeUpdatesConfigWithoutRebuildingPins() {
    val graph = NodeGraph()
    val n = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero).also {
        it.config.putString("sourceType","REDSTONE"); it.config.putString("targetType","BOOL")
        it.config.putString("mode","any")
    }
    graph.add(n)
    val editor = EditorState(graph, BlockPos.ZERO)
    val originalInPinType = n.inputs.first().type
    editor.changeConvertMode(n.id, "threshold")
    val updated = editor.nodeFlow(n.id)!!.value
    assertEquals("threshold", updated.config.getString("mode"))
    assertEquals(originalInPinType, updated.inputs.first().type) // unchanged
}
```

- [ ] **Step 2: Update `changeConvertTypes` and add `changeConvertMode`**

In `EditorState.kt`, replace the existing `changeConvertTypes` body and add `changeConvertMode` immediately after. Also add a private companion helper `defaultConvertModeFor(source, target)`:

```kotlin
fun changeConvertTypes(
    id: dev.nitka.nodewire.graph.NodeId,
    source: dev.nitka.nodewire.graph.PinType,
    target: dev.nitka.nodewire.graph.PinType,
) {
    updateNode(id) { n ->
        val inputs  = listOf(dev.nitka.nodewire.graph.Pin("in",  "In",  source))
        val outputs = listOf(dev.nitka.nodewire.graph.Pin("out", "Out", target))
        val mode = defaultConvertModeFor(source, target)
        val newConfig = n.config.copy().apply {
            putString("sourceType", source.name)
            putString("targetType", target.name)
            putString("mode", mode)
        }
        n.copy(inputs = inputs, outputs = outputs, config = newConfig)
    }
    disconnectAllEdges(id)
}

fun changeConvertMode(id: dev.nitka.nodewire.graph.NodeId, mode: String) {
    updateNode(id) { n ->
        n.copy(config = n.config.copy().apply { putString("mode", mode) })
    }
}

private fun defaultConvertModeFor(
    s: dev.nitka.nodewire.graph.PinType,
    t: dev.nitka.nodewire.graph.PinType,
): String = when (s to t) {
    dev.nitka.nodewire.graph.PinType.INT      to dev.nitka.nodewire.graph.PinType.REDSTONE -> "clamp"
    dev.nitka.nodewire.graph.PinType.FLOAT    to dev.nitka.nodewire.graph.PinType.REDSTONE -> "scaled"
    dev.nitka.nodewire.graph.PinType.BOOL     to dev.nitka.nodewire.graph.PinType.REDSTONE -> "hi"
    dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.INT      -> "raw"
    dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.FLOAT    -> "normalized"
    dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.BOOL     -> "any"
    else -> ""
}
```

- [ ] **Step 3: Run tests, green, commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateConvertTest"
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateConvertTest.kt
git commit -m "feat(editor): Convert mutators handle REDSTONE pairs + mode"
```

---

### Task 3: Extend `NodeConfigContent.Convert` for REDSTONE + Mode

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Step 1: Update `CONVERT_SOURCES` to include `REDSTONE`**

Find the existing `CONVERT_SOURCES` and `validTargetsFor` in `NodeConfigContent.kt`. Replace per spec:

```kotlin
private val CONVERT_SOURCES = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)

private fun validTargetsFor(src: PinType): List<PinType> = when (src) {
    PinType.INT      -> listOf(PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)
    PinType.FLOAT    -> listOf(PinType.INT, PinType.REDSTONE)
    PinType.BOOL     -> listOf(PinType.INT, PinType.REDSTONE)
    PinType.REDSTONE -> listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)
    else -> emptyList()
}

private fun convertModesFor(src: PinType, tgt: PinType): List<String> = when (src to tgt) {
    PinType.INT      to PinType.REDSTONE -> listOf("clamp", "modulo", "threshold", "scaled")
    PinType.FLOAT    to PinType.REDSTONE -> listOf("threshold", "scaled")
    PinType.BOOL     to PinType.REDSTONE -> listOf("hi", "level")
    PinType.REDSTONE to PinType.INT      -> listOf("raw", "scaled")
    PinType.REDSTONE to PinType.FLOAT    -> listOf("normalized", "raw", "scaled")
    PinType.REDSTONE to PinType.BOOL     -> listOf("any", "threshold")
    else -> emptyList()
}
```

- [ ] **Step 2: Add `Mode` row + `ConvertModeParams` Composable**

Update the existing `Convert: @Composable (Node) -> Unit` to render a third row (Mode) when `convertModesFor(source, target).isNotEmpty()`, and a `ConvertModeParams` block under it:

```kotlin
val Convert: @Composable (Node) -> Unit = { node ->
    val editor = LocalEditorState.current
    var source by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("sourceType").ifEmpty { PinType.INT.name }))
    }
    var target by remember(node.id) {
        mutableStateOf(PinType.fromName(node.config.getString("targetType").ifEmpty { PinType.FLOAT.name }))
    }
    var mode by remember(node.id) {
        mutableStateOf(node.config.getString("mode"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        LabeledRow("From") {
            Select(
                options = CONVERT_SOURCES,
                selected = source,
                onSelect = { next ->
                    source = next
                    val validTargets = validTargetsFor(next)
                    val newTarget = if (target in validTargets) target else validTargets.first()
                    target = newTarget
                    editor?.changeConvertTypes(node.id, next, newTarget)
                    // Sync local mode after mutator default-applies it.
                    mode = node.config.getString("mode") // will be empty or default
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
                    mode = node.config.getString("mode")
                },
                label = { it.name.lowercase() },
            )
        }
        val modes = convertModesFor(source, target)
        if (modes.isNotEmpty()) {
            LabeledRow("Mode") {
                Select(
                    options = modes,
                    selected = mode.ifEmpty { modes.first() },
                    onSelect = { next ->
                        mode = next
                        editor?.changeConvertMode(node.id, next)
                    },
                    label = { it },
                )
            }
            ConvertModeParams(node, source, target, mode.ifEmpty { modes.first() }, editor)
        }
    }
}

@Composable
private fun ConvertModeParams(node: Node, source: PinType, target: PinType, mode: String, editor: EditorState?) {
    when {
        // *-to-REDSTONE
        source == PinType.INT   && target == PinType.REDSTONE && mode == "threshold" ->
            IntField(node, "threshold", "Threshold", editor)
        source == PinType.INT   && target == PinType.REDSTONE && mode == "scaled" -> {
            IntField(node, "min", "Min", editor); IntField(node, "max", "Max", editor)
        }
        source == PinType.FLOAT && target == PinType.REDSTONE && mode == "threshold" ->
            FloatField(node, "thresholdF", "Threshold", editor)
        source == PinType.FLOAT && target == PinType.REDSTONE && mode == "scaled" -> {
            FloatField(node, "minF", "Min", editor); FloatField(node, "maxF", "Max", editor)
        }
        source == PinType.BOOL  && target == PinType.REDSTONE && mode == "level" ->
            IntField(node, "level", "Level", editor)
        // REDSTONE-to-*
        source == PinType.REDSTONE && target == PinType.INT   && mode == "scaled" -> {
            IntField(node, "min", "Min", editor); IntField(node, "max", "Max", editor)
        }
        source == PinType.REDSTONE && target == PinType.FLOAT && mode == "scaled" -> {
            FloatField(node, "minF", "Min", editor); FloatField(node, "maxF", "Max", editor)
        }
        source == PinType.REDSTONE && target == PinType.BOOL  && mode == "threshold" ->
            IntField(node, "threshold", "Threshold", editor)
    }
}
```

If the existing `IntField`/`FloatField` helpers in this file take a different signature, adapt accordingly — they're shared by `ConvertToRedstone`/`FromRedstone` historical Composables and other config bodies.

- [ ] **Step 3: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "feat(ui): Convert config UI gains Mode row and per-pair params"
```

---

### Task 4: Update CONVERT defaultConfig to seed all slots

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`

The existing `CONVERT` `nodeType(...)` block has a minimal `defaultConfig` (only `sourceType`+`targetType`). Add the slots used by the new modes so freshly placed nodes have reasonable defaults.

- [ ] **Step 1: Replace `defaultConfig`**

In the `CONVERT` definition:

```kotlin
defaultConfig = {
    CompoundTag().apply {
        putString("sourceType", PinType.INT.name)
        putString("targetType", PinType.FLOAT.name)
        putString("mode", "")
        putInt("threshold", 1)
        putFloat("thresholdF", 1f)
        putInt("min", 0)
        putInt("max", 15)
        putFloat("minF", 0f)
        putFloat("maxF", 1f)
        putInt("level", 15)
    }
},
```

- [ ] **Step 2: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt
git commit -m "feat(graph): Convert defaultConfig seeds redstone mode slots"
```

---

### Task 5: Delete `CONVERT_TO_REDSTONE` + `FROM_REDSTONE` + their evaluators + their UI Composables + their tests

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`
- Delete: `src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsFromRedstoneTest.kt`
- Delete: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFromRedstoneTest.kt`

- [ ] **Step 1: Delete node declarations from `StockNodeTypes.kt`**

Remove the `CONVERT_TO_REDSTONE` and `FROM_REDSTONE` `val` blocks. In `registerAll()`'s `// Conversion` group, drop both IDs — only `CONVERT` remains.

- [ ] **Step 2: Delete evaluators from `StockEvaluators.kt`**

Delete `ConvertToRedstone: NodeEvaluator` and `FromRedstone: NodeEvaluator`.

- [ ] **Step 3: Delete UI Composables**

In `NodeConfigContent.kt`, remove the public `ConvertToRedstone` and `FromRedstone` Composables. Also remove any now-orphaned helpers used only by them. Run `grep` after to confirm: `grep -n "ConvertToRedstone\|FromRedstone\|SOURCE_TYPES\|TARGET_TYPES\|defaultModeFor\|modesFor\b\|defaultTargetModeFor\|modesForTarget\|ModeParams\b\|FromRedstoneModeParams" src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`. Anything used only by the deleted Composables → delete. Anything still referenced (e.g. `IntField`, `FloatField`, `LabeledRow`, `Select`) → keep.

Note: `ConvertModeParams` (new in Task 3) replaces both old `ModeParams` and `FromRedstoneModeParams`. Make sure the old `ModeParams` function and the `FromRedstoneModeParams` function are deleted if they're no longer referenced.

- [ ] **Step 4: Delete the redstone-specific EditorState mutators if any survive**

Run `grep -n "changeConverterInput\|changeFromRedstoneOutput" src`. If any remain referenced — they used to be called by the deleted Composables — delete them from `EditorState.kt` too. Otherwise `grep` is empty and no action needed.

- [ ] **Step 5: Delete obsolete test files**

```bash
rm src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsFromRedstoneTest.kt
rm src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFromRedstoneTest.kt
```

- [ ] **Step 6: Update `StockNodeTypesTest` count**

Current expected count is 26. Removing 2 → expected 24. (Phase 2 of this plan will remove 6 more legacy math; we update again in Task 7.) For now set to 24.

- [ ] **Step 7: Build, commit**

```bash
./gradlew build
git add -A
git commit -m "$(cat <<'EOF'
refactor(graph): delete CONVERT_TO_REDSTONE and FROM_REDSTONE — subsumed by Convert

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Update CodecRoundTripTest for the new Convert shape

**Files:**
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt` (or wherever the test lives — find via grep)

- [ ] **Step 1: Replace any Convert-related roundtrip with a REDSTONE pair**

Find the existing `Convert` roundtrip case (added in the consolidation Phase 6 — it used `sourceType=BOOL, targetType=INT`). Replace or add cases that exercise the new REDSTONE pairs, e.g.:

- `Convert` with `sourceType=INT, targetType=REDSTONE, mode=scaled, min=0, max=100`
- `Convert` with `sourceType=REDSTONE, targetType=FLOAT, mode=normalized`

Match the existing test's assertion style.

If any roundtrip cases still reference deleted `CONVERT_TO_REDSTONE` or `FROM_REDSTONE` IDs, delete them.

- [ ] **Step 2: Build, commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"
git add -A
git commit -m "test(graph): cover REDSTONE pairs in Convert codec roundtrip"
```

---

### Task 7: Delete legacy single-type math nodes

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/StockNodeTypesTest.kt`

Nodes to delete: `ADD_VEC3`, `NEG_FLOAT`, `ABS_FLOAT`, `MIN_FLOAT`, `MAX_FLOAT`, `CLAMP_FLOAT`.

- [ ] **Step 1: Delete the six `val` declarations + the helpers in `StockNodeTypes.kt`**

Remove the six declarations. Then delete `floatBinary(...)`, `floatUnary(...)`, and `intBinary(...)` helper functions if they're no longer referenced. Run `grep -n "floatBinary\|floatUnary\|intBinary" src` — if any caller survives, leave the helper alone (and report it as a deviation).

Update `registerAll()`'s `// Math` group: drop the six IDs. `MATH` and `COMPARE` remain.

- [ ] **Step 2: Delete the six evaluators in `StockEvaluators.kt`**

Delete `AddVec3`, `NegFloat`, `AbsFloat`, `MinFloat`, `MaxFloat`, `ClampFloat`.

- [ ] **Step 3: Sweep callsites**

```bash
grep -rn "ADD_VEC3\|NEG_FLOAT\|ABS_FLOAT\|MIN_FLOAT\|MAX_FLOAT\|CLAMP_FLOAT" src
grep -rn "StockEvaluators\.\(AddVec3\|NegFloat\|AbsFloat\|MinFloat\|MaxFloat\|ClampFloat\)\b" src
```

Both must be empty. Fix any matches.

- [ ] **Step 4: Update `StockNodeTypesTest`**

Count: was 24 after Task 5; now 24 - 6 = **18**.

If the test has `categoriesAreCovered` or similar assertions, verify that the MATH category still has members (`MATH`, `COMPARE` are 2). Adjust thresholds if needed.

- [ ] **Step 5: Build, commit**

```bash
./gradlew build
git add -A
git commit -m "$(cat <<'EOF'
refactor(graph): delete legacy single-type math nodes (vec3/neg/abs/min/max/clamp)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Final verification

- [ ] **Step 1: Full build + tests**

`./gradlew build` → BUILD SUCCESSFUL. Count assertion in `StockNodeTypesTest` should be 18.

- [ ] **Step 2: Orphan sweep**

```bash
grep -rn "ConvertToRedstone\|FromRedstone\|fromRedstone\|convertToRedstone\|changeConverterInput\|changeFromRedstoneOutput" src
grep -rn "AddVec3\|NegFloat\|AbsFloat\|MinFloat\|MaxFloat\|ClampFloat" src
grep -rn "ADD_VEC3\|NEG_FLOAT\|ABS_FLOAT\|MIN_FLOAT\|MAX_FLOAT\|CLAMP_FLOAT\|CONVERT_TO_REDSTONE\|FROM_REDSTONE" src
```

Each must return empty.

- [ ] **Step 3: Hand-off note**

Smoke test in-game:
- Add `Convert` → set From = REDSTONE → To options should be INT/FLOAT/BOOL.
- Switch To = INT → Mode dropdown should appear with `raw`/`scaled`. Pick `scaled` → Min/Max fields appear.
- Switch From back to INT → To becomes FLOAT/BOOL/REDSTONE; pick REDSTONE → Mode = clamp/modulo/threshold/scaled.
- Switch From=INT, To=BOOL → no Mode row (cast pair).
- Add Node menu's Math category contains exactly: Math, Compare. No more Add Vec3, Negate Float, Abs Float, Min Float, Max Float, Clamp Float.
- Conversion category contains exactly: Convert.
