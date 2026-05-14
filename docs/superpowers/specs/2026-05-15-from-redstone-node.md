# From Redstone Node — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Add a `From Redstone` node that mirrors the existing `Convert To Redstone` node — a single configurable converter that turns a vanilla redstone signal (0..15) into a typed value (`INT` / `FLOAT` / `BOOL`). The output pin's type is driven by `config.targetType`; the conversion math is driven by `config.mode`.

Both directions now share the same shape: one configurable node per direction instead of N type-specific nodes.

## Non-goals

- No separate `RedstoneToBool` / `RedstoneToInt` / `RedstoneToFloat` nodes — one configurable node covers all three (mirrors `ConvertToRedstone`).
- No changes to `ConvertToRedstone` (already configurable).
- No new wire visualization gating, no label restyle — separate spec.

## Node definition

```
id           : from_redstone
displayName  : "From Redstone"
category     : CONVERSION
inputs       : [in : REDSTONE]                  (fixed)
outputs      : [out : <typeFromConfig>]         (driven by config.targetType)

config:
  targetType : "INT" | "FLOAT" | "BOOL"         (default "INT")
  mode       : per targetType (see below)       (default per type)
  threshold  : Int                              (BOOL/threshold only)
  min        : Int or Float                     (INT/FLOAT scaled only)
  max        : Int or Float                     (INT/FLOAT scaled only)
```

### Modes per target type

| targetType | modes                          | default      |
|------------|--------------------------------|--------------|
| INT        | `raw`, `scaled`                | `raw`        |
| FLOAT      | `normalized`, `raw`, `scaled`  | `normalized` |
| BOOL       | `any`, `threshold`             | `any`        |

### Evaluator semantics

Input `signal` is `Int` clamped to `0..15` (read from the REDSTONE input pin).

- **INT / raw** → `signal`
- **INT / scaled** → `lerp(signal, 0..15 → min..max)`, integer; if `max == min` → `min`.
- **FLOAT / normalized** → `signal / 15f`
- **FLOAT / raw** → `signal.toFloat()`
- **FLOAT / scaled** → `lerp(signal, 0..15 → min..max)`, float; if `max == min` → `min`.
- **BOOL / any** → `signal > 0`
- **BOOL / threshold** → `signal >= threshold`

Default config (freshly placed node):

```kotlin
CompoundTag().apply {
    putString("targetType", PinType.INT.name)
    putString("mode", "raw")
    putInt("threshold", 1)
    putInt("min", 0)
    putInt("max", 15)
}
```

(`min`/`max` slots are seeded for both Int and Float; the active read picks `getInt`/`getFloat` based on the current `targetType`. Switching INT↔FLOAT keeps the user-entered value in the same slot — fresh node default is identical for both modes.)

## EditorState API

Add a mirror of the existing `changeConverterInput`:

```kotlin
/**
 * From-Redstone has a single output pin whose type follows
 * `config.targetType`. Rebuild the pin and snip incompatible edges.
 */
fun changeFromRedstoneOutput(id: NodeId, newType: PinType) {
    updateNode(id) { n ->
        n.copy(outputs = listOf(n.outputs.first().copy(type = newType)))
    }
    disconnectAllEdges(id)
}
```

`config` mutation (writing `targetType` + reset `mode`) lives in the Composable callsite — same pattern as `ConvertToRedstone`'s "From" select.

## Config UI

Composable named `FromRedstone` inside `NodeConfigContent`, structurally identical to `ConvertToRedstone`:

1. `LabeledRow("To")` with a `Select(TARGET_TYPES, ...)` — on change: write `config.targetType`, reset `config.mode` to default for new type, call `editor.changeFromRedstoneOutput(node.id, next)`.
2. `LabeledRow("Mode")` with a `Select(modesForTarget(targetType), ...)` — on change: write `config.mode`.
3. `FromRedstoneModeParams(node, targetType, mode, editor)` — renders `threshold` / `min` / `max` fields under the modes that need them, using the existing private `IntField`/`FloatField` helpers.

Helpers (private inside `NodeConfigContent`):

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
```

These are new constants, not a refactor of CTR's `SOURCE_TYPES` / `defaultModeFor` / `modesFor`. The modes differ enough (CTR: `clamp`/`modulo`/`threshold`/`scaled`/`hi`/`level`; FromRedstone: `raw`/`scaled`/`normalized`/`any`/`threshold`) that a shared helper would be a tangle of `if (direction == ...)`.

## Registration

`StockNodeTypes.kt`:

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

Append to `registerAll()` in the CONVERSION group, next to `CONVERT_TO_REDSTONE`.

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — `FROM_REDSTONE` constant + `registerAll`.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` — `FromRedstone: NodeEvaluator`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — `FromRedstone` Composable + 3 helpers + `FromRedstoneModeParams`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — `changeFromRedstoneOutput`.

## Tests

`src/test/kotlin/dev/nitka/nodewire/graph/StockEvaluatorsFromRedstoneTest.kt` (new) — 7 cases:

| name                          | targetType | mode        | signal | extra config       | expected           |
|-------------------------------|------------|-------------|--------|--------------------|--------------------|
| intRawPassesThrough           | INT        | raw         | 7      | —                  | `Int(7)`           |
| intScaledLerpsIntoRange       | INT        | scaled      | 15     | min=0,max=100      | `Int(100)`         |
| floatNormalizedDividesBy15    | FLOAT      | normalized  | 15     | —                  | `Float(1.0)`       |
| floatRawCastsToFloat          | FLOAT      | raw         | 7      | —                  | `Float(7.0)`       |
| floatScaledLerps              | FLOAT      | scaled      | 0      | min=-1,max=1       | `Float(-1.0)`      |
| boolAnyIsSignalGtZero         | BOOL       | any         | 1      | —                  | `Bool(true)`       |
| boolThresholdRespectsConfig   | BOOL       | threshold   | 7      | threshold=8        | `Bool(false)`      |

`src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFromRedstoneTest.kt` (new) — 1 case:

- `changeFromRedstoneOutputRebuildsPinAndDisconnects` — start with a `from_redstone` node + an outgoing edge to an INT consumer; call `changeFromRedstoneOutput(id, PinType.BOOL)`; assert: output pin type is BOOL, edge list no longer contains the prior edge, the node's emitted flow snapshot reflects the new pin type.

No new evaluator coverage for `ConvertToRedstone` — unchanged.

## Roundtrip

`Node.CODEC` already serializes `inputs`/`outputs`/`config` as-is — no codec change needed. After save+load, the `outputs[0].type` is whatever was set + `config.targetType` matches it. Existing `CodecRoundTripTest` style is sufficient if extended; not required by this spec (the existing infra already covers it generically).

## Out of scope (deferred)

- Wire visualization gating on "redstone in hand".
- Label restyle (drop background plate).
- Refactoring CTR and FromRedstone to share helpers.
- Other redstone-adjacent nodes (analog gates, comparator-style subtractors, etc.).
