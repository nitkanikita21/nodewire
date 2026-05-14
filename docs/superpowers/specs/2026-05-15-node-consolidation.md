# Node Consolidation — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Collapse 27 type/op-specific stock nodes into 5 configurable nodes. Reduces the Add Node menu from ~45 entries to ~23 without losing any expressiveness.

| Consolidated node | Replaces (count) |
|---|---|
| **`constant`** | `bool_const`, `int_const`, `float_const`, `string_const`, `vec3_const` (5) |
| **`logic_gate`** | `and`, `or`, `not`, `xor`, `nand`, `nor`, `xnor` (7) |
| **`math`** | `add_int`, `add_float`, `sub_int`, `sub_float`, `mul_int`, `mul_float`, `div_int`, `div_float`, `mod_int` (9) |
| **`compare`** | `compare_int`, `compare_float` (2) |
| **`convert`** | `int_to_float`, `float_to_int`, `bool_to_int`, `int_to_bool` (4) |

## Non-goals

- No consolidation of: `side_input/output`, `channel_input/output`, `convert_to_redstone`, `from_redstone`, `add_vec3`, `neg_float`, `abs_float`, `min_float`, `max_float`, `clamp_float`, `timer`, `pulse`, `random_bool`, `random_int`, `edge_rising`, `toggle`, `counter`, `delay`, `select_bool`. Different shapes (unary/3-arity/single-type/state-bearing) — separate consolidation if ever needed.
- No backward-compat shim. Old node IDs are **gone**. Saved graphs containing them will drop those nodes on load (the registry returns null for unknown IDs — existing behavior). Fits the codebase's clean-slate stance.
- No UI changes to category labels.

## Migration

Clean slate. Old `nodeType.id` strings (`int_const`, `add_float`, etc.) are deleted from `StockNodeTypes`. Existing tests referencing them are rewritten against the new consolidated nodes. `CodecRoundTripTest` is updated to roundtrip the new shapes.

---

## Node 1: `constant`

```
id           : constant
displayName  : "Constant"
category     : CONSTANTS
inputs       : []
outputs      : [out : <typeFromConfig>]
config:
  type    : "BOOL" | "INT" | "FLOAT" | "STRING" | "VEC3"   (default "BOOL")
  bool    : Boolean (BOOL slot)
  int     : Int     (INT slot)
  float   : Float   (FLOAT slot)
  string  : String  (STRING slot)
  x, y, z : Float   (VEC3 slot)
```

**One slot per type** in config. Switching types keeps prior values (so the user doesn't lose work when probing).

**Evaluator:** read `type`, return `PinValue.<T>` from the matching config slot. For unknown type → `PinValue.default(BOOL)`.

**EditorState mutator:** `changeConstantType(id, newType)` — rebuilds output pin to `newType` and disconnects outgoing edges (consistent with `changeChannelType` / `changeConverterInput`).

**UI:** `Select("Type", CONSTANT_TYPES)` + a value field switched on current type:

- BOOL → `Checkbox`
- INT → `IntField` over `config.int`
- FLOAT → `FloatField` over `config.float`
- STRING → `TextInput` over `config.string`
- VEC3 → three `FloatField`s for `x`, `y`, `z`

---

## Node 2: `logic_gate`

```
id           : logic_gate
displayName  : "Logic Gate"
category     : LOGIC
inputs       : NOT → [in: BOOL]
               binary ops → [a: BOOL, b: BOOL]
outputs      : [out : BOOL]
config:
  op : "AND" | "OR" | "NOT" | "XOR" | "NAND" | "NOR" | "XNOR"  (default "AND")
```

**Pin rebuild on op change:** switching to NOT rebuilds inputs to `[Pin("in", "In", BOOL)]`; switching to any other op rebuilds to `[Pin("a", "A", BOOL), Pin("b", "B", BOOL)]`. The mutator must disconnect all edges touching this node — pin IDs change, so edges become stale.

**Evaluator:** reuse the existing 7 evaluator implementations behind a single dispatcher:

```kotlin
val LogicGate: NodeEvaluator = { config, inputs ->
    val op = config.getString("op").ifEmpty { "AND" }
    val out = when (op) {
        "NOT" -> !boolIn(inputs, "in")
        "AND" -> boolIn(inputs, "a") && boolIn(inputs, "b")
        "OR"  -> boolIn(inputs, "a") || boolIn(inputs, "b")
        "XOR" -> boolIn(inputs, "a") xor boolIn(inputs, "b")
        "NAND" -> !(boolIn(inputs, "a") && boolIn(inputs, "b"))
        "NOR"  -> !(boolIn(inputs, "a") || boolIn(inputs, "b"))
        "XNOR" -> !(boolIn(inputs, "a") xor boolIn(inputs, "b"))
        else -> false
    }
    mapOf("out" to PinValue.Bool(out))
}
```

**EditorState mutator:** `changeLogicGateOp(id, newOp)` — writes `config.op`, rebuilds inputs as above, disconnects all edges.

**UI:** `Select("Op", LOGIC_OPS)`. No other config fields.

---

## Node 3: `math`

```
id           : math
displayName  : "Math"
category     : MATH
inputs       : [a : <type>, b : <type>]
outputs      : [out : <type>]
config:
  op   : "ADD" | "SUB" | "MUL" | "DIV" | "MOD"   (default "ADD")
  type : "INT" | "FLOAT"                          (default "INT")
```

**Constraints:**
- `MOD` is valid only for `INT`. UI hides MOD when type is FLOAT. Switching type INT→FLOAT while op=MOD resets op to ADD.
- DIV by zero behavior matches existing: INT → 0 (Kotlin `Int.div` throws; evaluator must guard), FLOAT → `Float.NaN` is acceptable but for symmetry returns 0f when `b == 0f`.

**Evaluator:** dispatch on type then op. Inputs and output share type.

**EditorState mutator:** `changeMathConfig(id, op, type)` — single mutator covers both axes:
- Rebuilds inputs to `[a: type, b: type]` and outputs to `[out: type]`.
- Writes `config.op` + `config.type`.
- Disconnects all edges (pin types may have changed).

The Composable calls this with the merged tuple after either selector fires.

**UI:** `Select("Op", opsForType(type))` + `Select("Type", MATH_TYPES)`. `opsForType` returns `[ADD, SUB, MUL, DIV, MOD]` for INT and `[ADD, SUB, MUL, DIV]` for FLOAT.

---

## Node 4: `compare`

```
id           : compare
displayName  : "Compare"
category     : MATH
inputs       : [a : <type>, b : <type>]
outputs      : [gt : BOOL, eq : BOOL, lt : BOOL]
config:
  type : "INT" | "FLOAT"   (default "INT")
```

**Outputs are always 3 booleans regardless of type** — preserves the existing UX where one node tells you which of three relations holds. Pin IDs (`gt`/`eq`/`lt`) are fixed, so the outputs don't need to be rebuilt on type change; only the input pin types change.

**Evaluator:** dispatch on type, compute three booleans, return all three.

**EditorState mutator:** `changeCompareType(id, newType)` — rebuilds inputs to `[a: newType, b: newType]`, writes `config.type`, disconnects edges touching the inputs only (output pin IDs unchanged, but for simplicity reuse `disconnectAllEdges(id)` — symmetric with other mutators, and outputs are bool so usually still compatible — see "edge handling" below).

**Edge handling decision:** disconnect *all* edges on type change, even outputs. Reason: keeps the mutator family uniform (`changeChannelType`, `changeConverterInput`, `changeFromRedstoneOutput` all do this). The cost is one re-wire of `gt/eq/lt` consumers — acceptable since a type change is a deliberate action.

**UI:** `Select("Type", MATH_TYPES)`.

---

## Node 5: `convert`

```
id           : convert
displayName  : "Convert"
category     : CONVERSION
inputs       : [in : <sourceType>]
outputs      : [out : <targetType>]
config:
  sourceType : "INT" | "FLOAT" | "BOOL"   (default "INT")
  targetType : "INT" | "FLOAT" | "BOOL"   (default "FLOAT")
```

**Valid pairs** — same coverage as the 4 deleted nodes:
- INT ↔ FLOAT
- INT ↔ BOOL

(BOOL ↔ FLOAT is *not* one of the existing 4 — keeping parity. The UI restricts `targetType` options to those valid for the current `sourceType`; pseudocode `validTargetsFor(source)`.)

| source | valid targets |
|---|---|
| INT   | FLOAT, BOOL |
| FLOAT | INT |
| BOOL  | INT |

If the user picks a `sourceType` that invalidates the current `targetType`, the Composable resets `targetType` to the first valid option for the new source.

**Evaluator:** dispatch on `(sourceType, targetType)` and call the existing per-pair conversion logic (just inline it from the deleted evaluators).

**EditorState mutator:** `changeConvertTypes(id, source, target)` — rebuilds input + output pins, writes both config keys, disconnects edges.

**UI:** `Select("From", CONVERT_SOURCES)` + `Select("To", validTargetsFor(source))`.

---

## Shared bits

### `EditorState`

Five new mutators following the existing pattern (cf. `changeFromRedstoneOutput`):

```kotlin
fun changeConstantType(id: NodeId, newType: PinType) { ... }
fun changeLogicGateOp(id: NodeId, newOp: String) { ... }
fun changeMathConfig(id: NodeId, op: String, type: PinType) { ... }
fun changeCompareType(id: NodeId, newType: PinType) { ... }
fun changeConvertTypes(id: NodeId, source: PinType, target: PinType) { ... }
```

All five end with `disconnectAllEdges(id)`.

### `NodeConfigContent`

Five new Composables, each following the structure of the existing `ConvertToRedstone` / `FromRedstone` (private helpers `LabeledRow`, `Select`, `IntField`, `FloatField`, `TextInput` already exist and are reused).

The existing per-type constant Composables (`BoolConst`, `IntConst`, `FloatConst`, `StringConst`, `Probability`, `IntRange`, `TimerPeriod`, etc.) that are still referenced by other nodes (`Random Bool`, `Timer`, `Pulse`, etc.) stay. Only the 5 deleted nodes' Composables (`BoolConst`, `IntConst`, `FloatConst`, `StringConst` — none for `VEC3_CONST` because it has none) lose their original callsites. Their Composables remain in the file as building blocks reused by the new `Constant` Composable via a `when (type)` dispatch — no duplication of input UI.

### Categories

- `Constant` → `CONSTANTS` (replaces 5)
- `LogicGate` → `LOGIC` (replaces 7)
- `Math` → `MATH` (replaces 9 — note: deleted nodes were in MATH)
- `Compare` → `MATH` (replaces 2 — were in MATH)
- `Convert` → `CONVERSION` (replaces 4 — were in CONVERSION)

`registerAll()` shrinks accordingly.

---

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Tests: rewrite/extend any that reference the 27 deleted nodes (`StockNodeTypesTest`, `StockEvaluatorsTest`, `EditorStateFlowTest`, `CodecRoundTripTest`, `NodeGraphEvaluatorTest`, etc.). Concrete list materialises during implementation; the plan instructs the implementer to `grep` for each deleted ID and update callsites.

## Tests

New evaluator tests (one file per consolidated node, e.g. `StockEvaluatorsConstantTest`, `StockEvaluatorsLogicGateTest`, `StockEvaluatorsMathTest`, `StockEvaluatorsCompareTest`, `StockEvaluatorsConvertTest`). Coverage: every (type × op) combination at least once.

New EditorState mutator tests: one per new mutator, each verifying pin rebuild + edge disconnect.

Existing tests for deleted nodes: deleted or rewritten against the new shapes — clean slate, no aliases.

`CodecRoundTripTest` extended to roundtrip one instance of each new consolidated node with non-default config to cover the new config slots.

## Out of scope (deferred)

- Other consolidations (NEG/ABS/MIN/MAX/CLAMP_FLOAT; TIMER+PULSE; RANDOM_BOOL+RANDOM_INT).
- Add Node menu redesign (categorization improvements).
- Migration helpers to remap old graph IDs to new ones.
