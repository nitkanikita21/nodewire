# Convert Absorbs Redstone + Purge Legacy Math — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Two related cleanups in one pass:

1. **Purge legacy single-type math nodes** from the Add Node menu: `ADD_VEC3`, `NEG_FLOAT`, `ABS_FLOAT`, `MIN_FLOAT`, `MAX_FLOAT`, `CLAMP_FLOAT` (6 nodes). These are leftover from before the `Math` consolidation; they clutter the Math category and have no consolidated replacement yet. Clean slate — if the user needs them later they come back as a separate consolidated node (`UnaryMath`, `Vec3Math`).

2. **Absorb `CONVERT_TO_REDSTONE` and `FROM_REDSTONE` into the existing `CONVERT` node** so all type conversions live behind one configurable node. The new `CONVERT` adds REDSTONE as a valid source and target type, with per-pair mode config carrying over the existing semantics.

## Non-goals

- No FLOAT↔BOOL direct conversion. Existing `CONVERT` didn't have it; preserve parity. Users route via INT.
- No legacy-replacement nodes (UnaryMath, Vec3Math, etc.) — separate future work.
- No SELECT_BOOL, EDGE_RISING, TOGGLE, COUNTER, DELAY, RANDOM_*, PULSE changes — flow/generator nodes stay.
- No migration shims. Graphs containing deleted node IDs lose those nodes on load (NodeTypeRegistry returns null → skipped — existing behavior).

## Convert node v2

```
id           : convert
displayName  : "Convert"
category     : CONVERSION
inputs       : [in : <sourceType>]
outputs      : [out : <targetType>]
config:
  sourceType : INT | FLOAT | BOOL | REDSTONE     (default INT)
  targetType : INT | FLOAT | BOOL | REDSTONE     (default FLOAT)
  mode       : string — semantics depend on (source, target); empty for
               no-mode pairs
  threshold  : Int     (used by threshold modes — slot reused for both INT and Redstone threshold; written as Int even when matching against Float input)
  thresholdF : Float   (FLOAT → REDSTONE threshold)
  min, max   : Int     (INT/REDSTONE scaled)
  minF, maxF : Float   (FLOAT/REDSTONE scaled)
  level      : Int     (BOOL → REDSTONE "level" mode: true → level, false → 0)
```

(Separate Int/Float slots avoid NBT type churn when switching pair direction. Reuse where the slot's type is unambiguous — `min`/`max` for INT and REDSTONE share the Int slot.)

### Valid pairs and modes

| source → target | modes | mode params |
|---|---|---|
| INT → FLOAT | — | — |
| FLOAT → INT | — | — |
| INT → BOOL | — | — |
| BOOL → INT | — | — |
| INT → REDSTONE | `clamp`, `modulo`, `threshold`, `scaled` | threshold (Int); min, max (Int) for scaled |
| FLOAT → REDSTONE | `threshold`, `scaled` | thresholdF; minF, maxF for scaled |
| BOOL → REDSTONE | `hi`, `level` | level (Int) for level mode |
| REDSTONE → INT | `raw`, `scaled` | min, max (Int) for scaled |
| REDSTONE → FLOAT | `normalized`, `raw`, `scaled` | minF, maxF for scaled |
| REDSTONE → BOOL | `any`, `threshold` | threshold (Int) |

Same-type pairs (INT→INT, REDSTONE→REDSTONE, …) — UI filters them out. FLOAT↔BOOL stays unsupported.

### Evaluator semantics

The four no-mode pairs (cast pairs) are unchanged from the existing `Convert` evaluator.

The six mode-bearing pairs preserve the exact behavior of the deleted `ConvertToRedstone` / `FromRedstone` evaluators. Inline their logic into the new `Convert` dispatcher; do not keep the old evaluators.

| pair | mode | formula |
|---|---|---|
| INT→REDSTONE | clamp | `signal = x.coerceIn(0, 15)` |
| INT→REDSTONE | modulo | `((x % 16) + 16) % 16` |
| INT→REDSTONE | threshold | `if (x >= threshold) 15 else 0` |
| INT→REDSTONE | scaled | `(((x - min).toFloat() / (max - min)) * 15f).toInt().coerceIn(0,15)`; if `max==min` → 0 |
| FLOAT→REDSTONE | threshold | `if (x >= thresholdF) 15 else 0` |
| FLOAT→REDSTONE | scaled | `(((x - minF) / (maxF - minF)) * 15f).toInt().coerceIn(0,15)`; if `maxF==minF` → 0 |
| BOOL→REDSTONE | hi | `if (x) 15 else 0` |
| BOOL→REDSTONE | level | `if (x) level.coerceIn(0,15) else 0` |
| REDSTONE→INT | raw | `signal` (signal already coerced to 0..15) |
| REDSTONE→INT | scaled | `lerp(signal/15f → min..max)` (Int); if `max==min` → `min` |
| REDSTONE→FLOAT | raw | `signal.toFloat()` |
| REDSTONE→FLOAT | normalized | `signal / 15f` |
| REDSTONE→FLOAT | scaled | `lerp(signal/15f → minF..maxF)`; if `maxF==minF` → `minF` |
| REDSTONE→BOOL | any | `signal > 0` |
| REDSTONE→BOOL | threshold | `signal >= threshold` |

### EditorState mutator

Replace the existing `changeConvertTypes(id, source, target)` with a richer one that also resets `mode` to the default for the new pair:

```kotlin
fun changeConvertTypes(id: NodeId, source: PinType, target: PinType) {
    updateNode(id) { n ->
        val inputs  = listOf(Pin("in",  "In",  source))
        val outputs = listOf(Pin("out", "Out", target))
        val defaultMode = defaultModeFor(source, target)
        val newConfig = n.config.copy().apply {
            putString("sourceType", source.name)
            putString("targetType", target.name)
            putString("mode", defaultMode)
        }
        n.copy(inputs = inputs, outputs = outputs, config = newConfig)
    }
    disconnectAllEdges(id)
}
```

Add `changeConvertMode(id, mode)` for the Mode dropdown (just writes `config.mode`, no pin rebuild, no edge disconnect).

`defaultModeFor(source, target)` — helper in `EditorState` (private companion) returning the first mode in the table for mode-bearing pairs and `""` for cast pairs.

### UI

`NodeConfigContent.Convert` Composable extended:

1. `LabeledRow("From")` with `Select(CONVERT_SOURCES, ...)`.
2. `LabeledRow("To")` with `Select(validTargetsFor(source), ...)`.
3. `LabeledRow("Mode")` — only rendered when `(source, target)` has modes. `Select(modesFor(source, target), ...)`.
4. `ConvertModeParams(...)` — renders threshold/min/max/level fields under modes that need them. Reuse the existing private `IntField`/`FloatField` helpers.

Helpers (private in `NodeConfigContent`):

```kotlin
private val CONVERT_SOURCES = listOf(PinType.INT, PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)

private fun validTargetsFor(src: PinType): List<PinType> = when (src) {
    PinType.INT      -> listOf(PinType.FLOAT, PinType.BOOL, PinType.REDSTONE)
    PinType.FLOAT    -> listOf(PinType.INT, PinType.REDSTONE)
    PinType.BOOL     -> listOf(PinType.INT, PinType.REDSTONE)
    PinType.REDSTONE -> listOf(PinType.INT, PinType.FLOAT, PinType.BOOL)
    else -> emptyList()
}

private fun modesFor(src: PinType, tgt: PinType): List<String> = when (src to tgt) {
    PinType.INT      to PinType.REDSTONE -> listOf("clamp", "modulo", "threshold", "scaled")
    PinType.FLOAT    to PinType.REDSTONE -> listOf("threshold", "scaled")
    PinType.BOOL     to PinType.REDSTONE -> listOf("hi", "level")
    PinType.REDSTONE to PinType.INT      -> listOf("raw", "scaled")
    PinType.REDSTONE to PinType.FLOAT    -> listOf("normalized", "raw", "scaled")
    PinType.REDSTONE to PinType.BOOL     -> listOf("any", "threshold")
    else -> emptyList()
}
```

Use `to` operator pair matching with `when` for the (source, target) tuple.

## Legacy math purge

Delete six node declarations + their evaluators + their UI (if any):

- `ADD_VEC3` + `AddVec3` evaluator
- `NEG_FLOAT` + `NegFloat` evaluator
- `ABS_FLOAT` + `AbsFloat` evaluator
- `MIN_FLOAT` + `MinFloat` evaluator
- `MAX_FLOAT` + `MaxFloat` evaluator
- `CLAMP_FLOAT` + `ClampFloat` evaluator

After removal:
- `floatBinary(...)` helper in `StockNodeTypes.kt` is no longer used (was MIN/MAX float). Delete it.
- `floatUnary(...)` helper is no longer used (was NEG/ABS float). Delete it.
- `intBinary(...)` helper: check via grep — likely orphaned now too. Delete if no callers.

Update `registerAll()` to remove these six IDs.

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`
- Tests: existing `StockEvaluatorsConvertTest` + `EditorStateConvertTest` get expanded for new pairs. Delete `StockEvaluatorsFromRedstoneTest`, `EditorStateFromRedstoneTest` (their cases re-emerge as REDSTONE→… inside the new Convert tests). `StockNodeTypesTest` count update. `CodecRoundTripTest` update if it covers any of the deleted nodes.

## Tests

Expand `StockEvaluatorsConvertTest` to cover **every** mode-bearing pair × mode combination (15 mode rows × ≥1 case each = ≥15 new tests), plus regression cases for the four cast pairs.

Expand `EditorStateConvertTest`:
- `changeConvertTypesIntToRedstoneSetsClampMode` — switching to a mode-bearing pair populates `mode` with the default for that pair.
- `changeConvertModeUpdatesConfigWithoutRebuildingPins` — verifies pin types unchanged.
- (Optional) `changeConvertTypesBackToCastPairClearsMode` — empty `mode` slot when no modes apply.

Delete `StockEvaluatorsFromRedstoneTest` and `EditorStateFromRedstoneTest` entirely (clean slate; their coverage is subsumed).

## Final registry count

Pre: 26 (after Phase 6).
Removed: 6 legacy math + 2 redstone converters = 8.
Net: **18**.

Update `StockNodeTypesTest`.

## Out of scope (deferred)

- Replacement consolidated nodes for the removed legacy math (UnaryMath, Vec3Math, ClampMath).
- FLOAT↔BOOL direct conversion.
- Migration of old graph data — clean slate.
