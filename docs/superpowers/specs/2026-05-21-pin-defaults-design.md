# Inline Pin Defaults + Config-to-Pin Migration — Design

**Date:** 2026-05-21
**Scope:** Allow inline values on unconnected input pins (no separate Constant node needed), and migrate most config-only parameters of existing nodes into pins so they can be either inline-edited OR wired.

## Goal

Today a node like `Lerp(a, b, t)` requires three Constant nodes wired to its inputs just to compute a single value. Today Math's `op` enum lives only in the config sheet — you can pick it once, but you can't drive it from the graph (e.g. "switch between ADD and MUL based on a button"). Both problems share a fix: every input pin can carry its own inline value, and parameters that used to be config become input pins.

After this work:

1. Drop a `Lerp` on the canvas, click `t`, type `0.5`. Done — no Constant needed.
2. Wire a `Switch` to `Math.op` to pick the operation dynamically based on graph state.
3. Every existing node automatically gets inline editors on all its unconnected input pins — the change is to the framework, not per-node.

## Architecture

### `PinEditor` (declarative editor spec)

```kotlin
sealed class PinEditor {
    object Numeric : PinEditor()
    object Checkbox : PinEditor()
    object Text : PinEditor()
    data class Enum(val options: List<String>) : PinEditor()
    object Vector : PinEditor()
    data class Slider(val min: Float, val max: Float) : PinEditor()
    object None : PinEditor()
}
```

The renderer maps each variant to a small compose composable. Default editor derived from `PinType` when `Pin.editor == null`:

- `BOOL` → `Checkbox`
- `INT` / `FLOAT` / `REDSTONE` → `Numeric`
- `STRING` → `Text` (unless declared as `Enum`)
- `VEC2` / `VEC3` / `QUAT` → `Vector`
- `ANY` → `None`

`Slider` is declared explicitly per pin where a sane range is known (e.g. `factor: 0..1` on `Smooth`). Out of scope for this work — we ship `Numeric` everywhere for now; sliders are a follow-up cosmetic upgrade.

### `Pin` schema change

```kotlin
data class Pin(
    val id: String,
    val name: String,
    val type: PinType,
    val editor: PinEditor? = null,  // null → derive from type
)
```

Back-compat: every existing call site passes 3 args, default `editor = null` resolves to type-derived editor. No call site changes required for nodes that don't need a non-default editor (e.g. `LogicGate.op` requires `Enum(...)`).

### Pin-default storage

Pin defaults live in `Node.config` under a single sub-tag named `"pinDefaults"`:

```
Node.config = {
    op: "ADD",                  // pre-existing config keys (unrelated)
    pinDefaults: {
        a: { type: "float", v: 1.0 },     // PinValue.CODEC entries
        b: { type: "float", v: 2.0 },
        t: { type: "float", v: 0.5 },
    }
}
```

Two helpers on `Node`:

```kotlin
fun Node.getPinDefault(pinId: String): PinValue? {
    val defaults = config.get("pinDefaults") as? CompoundTag ?: return null
    val tag = defaults.get(pinId) ?: return null
    return PinValue.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null)
}

fun Node.withPinDefault(pinId: String, value: PinValue?): Node {
    val cfg = config.copy()
    val defaults = (cfg.get("pinDefaults") as? CompoundTag)?.copy() ?: CompoundTag()
    if (value == null) {
        defaults.remove(pinId)
    } else {
        PinValue.CODEC.encodeStart(NbtOps.INSTANCE, value).result().ifPresent {
            defaults.put(pinId, it)
        }
    }
    cfg.put("pinDefaults", defaults)
    return copy(config = cfg)
}
```

These live on `Node` (not the codec) so they read fresh from the current config every call.

### Evaluator integration

Both `StatefulGraphEvaluator` and `GraphEvaluator` have an edge-read loop that currently looks roughly like:

```kotlin
inputs[pin.id] = when {
    value == null -> PinValue.default(pin.type)
    pin.type == PinType.ANY -> value
    else -> PinValueConversion.convert(value, pin.type)
}
```

The fallback `PinValue.default(pin.type)` is replaced with the pin's stored default (if any), then auto-conversion still applies:

```kotlin
inputs[pin.id] = when {
    value == null -> {
        val pinDefault = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
        if (pin.type == PinType.ANY) pinDefault
        else PinValueConversion.convert(pinDefault, pin.type)
    }
    pin.type == PinType.ANY -> value
    else -> PinValueConversion.convert(value, pin.type)
}
```

This is the ONLY behavioural change in the evaluator. Wired pins behave identically.

### EditorState mutator

```kotlin
fun setPinDefault(
    id: NodeId,
    pinId: String,
    value: PinValue?,
) {
    mutateGraph(mergeable = true) {
        _updateNodeInternal(id) { it.withPinDefault(pinId, value) }
    }
    requestSave?.invoke()
}
```

`mergeable = true` so typing into a numeric editor collapses into one undo entry per "edit session".

### UI

Each input pin's row in `NodeCard` shows the existing handle + type-color label + (new) inline editor. The editor renders to the right of the name, with a width matched to the editor type:

- `Numeric` — 50 px text field, type-validated on commit (blur / Enter)
- `Checkbox` — 14 px toggle
- `Text` — 100 px text field
- `Enum` — clickable label showing the current value; opens a popup menu (existing `ContextMenu` machinery) with the options
- `Vector` — small grid of `Numeric` editors, one per component (2/3/4)
- `None` — render nothing (ANY pins, output pins always)

When an edge attaches to the pin, the editor is hidden — the pin shows wired value instead.

Editor commit hooks call `editor.setPinDefault(nodeId, pinId, parsedValue)`.

### Config-to-pin migration

For each migrated node:

1. Add an input pin with the appropriate type + `editor` spec.
2. Evaluator reads from `inputs["xyz"]` rather than `config.getString("xyz")`.
3. Codec back-compat shim (at `Node.CODEC` parse time): if the loaded `config` still has the old key AND no `pinDefaults` entry for the new pin, copy the value across.

The 7 migrations:

| Node | Old config keys | New pins (with editor) |
| ---- | --------------- | ----------------------- |
| `Math` | `op: String` (enum) | `op: STRING + Enum(ADD, SUB, MUL, DIV, MIN, MAX, ABS, SQRT, MOD, NEG, FLOOR, CEIL, ROUND)` |
| `Compare` | `op: String` (enum) | `op: STRING + Enum(EQ, NE, LT, LE, GT, GE)` |
| `LogicGate` | `op: String` (enum) | `op: STRING + Enum(AND, OR, NOT, XOR, NAND, NOR, XNOR)` |
| `Timer` | `period: Int` | `period: INT` (numeric) |
| `Probability` | `p: Float` | `p: FLOAT` (numeric) |
| `Delay` | `ticks: Int` | `ticks: INT` (numeric) |
| `Random Int` | `min: Int, max: Int` | `min: INT, max: INT` (numeric) |
| `PID` (advanced) | `i_min: Float, i_max: Float` | `i_min: FLOAT, i_max: FLOAT` (numeric) |

Each old `configContent` composable is removed — the pin-row editors replace them.

### What stays as config

Anything where the value drives pin reshape (so the value is structural, not a signal):

- `Switch.cases` (pin count)
- `Sequencer.steps` (modulo arithmetic + step count)
- `VecOp.op` + `VecOp.dim` (pin shape changes per op)
- `AeroInput.channel` (pin type changes per channel)
- `Convert.from` + `Convert.to` (pin types)
- `SideFace.face` (block-face is an editor concept, not a graph signal)
- `ChannelEndpoint.name` + `type` (cross-block routing key, not a tick-level signal)
- `RedstoneLinkFrequency` (item slots — custom UI)
- `Constant.value` (the node IS a constant)

These keep their existing `configContent` composables and their config-keyed serialization.

### Codec back-compat shim

Single insertion point in `Node.CODEC` (or in `LogicBlockEntity` load-side post-process — whichever has lower change surface). Pseudocode:

```kotlin
private val LEGACY_CONFIG_MIGRATIONS: Map<String, Map<String, String>> = mapOf(
    "math" to mapOf("op" to "op"),
    "compare" to mapOf("op" to "op"),
    "logic_gate" to mapOf("op" to "op"),
    "timer" to mapOf("period" to "period"),
    "probability" to mapOf("p" to "p"),
    "delay" to mapOf("ticks" to "ticks"),
    "random_int" to mapOf("min" to "min", "max" to "max"),
    "pid" to mapOf("i_min" to "i_min", "i_max" to "i_max"),
)

fun migrateLegacyConfig(node: Node): Node {
    val migrations = LEGACY_CONFIG_MIGRATIONS[node.typeKey.path] ?: return node
    var updated = node
    val defaults = (updated.config.get("pinDefaults") as? CompoundTag)
    for ((configKey, pinId) in migrations) {
        // Skip if user already has a pin-default for this pin (newer save).
        if (defaults?.contains(pinId) == true) continue
        // Skip if the config doesn't have the legacy key.
        if (!updated.config.contains(configKey)) continue
        val pinType = updated.inputs.firstOrNull { it.id == pinId }?.type ?: continue
        val value = readConfigAsPinValue(updated.config, configKey, pinType) ?: continue
        updated = updated.withPinDefault(pinId, value)
    }
    return updated
}
```

The migrator runs once at `Node.CODEC.decode` and produces a fully-modern Node. Re-saving the graph writes the new shape; subsequent loads skip the migration (no legacy keys).

### Testing

| Test | Verifies |
| ---- | -------- |
| `PinDefaultStorageTest` | `Node.withPinDefault / getPinDefault` round-trip for every PinType; setting `null` removes the entry; idempotent set-same-value |
| `EvaluatorPinDefaultTest` | Unwired input with pin-default → evaluator sees the default; with edge → default ignored; with neither → falls back to `PinValue.default(type)` |
| `PinEditorDeriveDefaultTest` | `Pin(type)` with `editor=null` resolves to the type-derived editor variant |
| `LegacyConfigMigrationTest` | Per migrated node: load a Node with the old config shape, evaluate, assert behaviour matches direct pin-default setting |
| Existing node tests | All still pass — evaluators of Math/Compare/etc. now read from `inputs[]` instead of `config`, but with the migration shim the test setup that puts `op` into config still works |

Manual in-client (deferred, separate task): for each migrated node, observe the editor in the pin row; type a value; verify the graph behaves correctly. For Math.op, open the dropdown, pick MUL, check the result.

## Versioning

**v0.4.0** (minor). Codec is back-compatible:

- Old graphs with config-only keys are silently migrated on load.
- New `pinDefaults` sub-tag is invisible to nodes that don't use it (codec ignores unknown keys per existing convention).
- `Pin.editor` is a new optional field — existing constructor calls still compile.
- Evaluator behaviour for already-correct (no inline default, all wired) graphs is bit-identical.

## Out of scope

- `Slider` editor — declared in the sealed class but no rendering implementation; ship `Numeric` for ranged floats too. Follow-up.
- `Color` editor — useful for future visual nodes, but no existing PinType for it.
- Group-level "expose this pin to parent" pattern — pin-defaults are per-node, not per-group.
- Per-pin label override (replacing the pin's name in the pin-row UI) — interesting follow-up but unrelated.

## Scope sizing

~12 implementation tasks across 3 phases. Comparable to algorithm-nodes work in v0.3.0.
