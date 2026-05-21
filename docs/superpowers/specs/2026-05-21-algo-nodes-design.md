# Algorithm Nodes + Generic Pins — Design

**Date:** 2026-05-21
**Scope:** Add a generic-pin type (`ANY`) plus implicit value conversion so a single node can serve every scalar pin type. Build a catalogue of 11 control-flow / math / state nodes on top — enough to express conditional logic, multi-way selection, latched state, simple state machines, range remapping, smoothing, and closed-loop control.

## Goal

Current node catalogue covers the basics — boolean logic, arithmetic, simple flow primitives (Rising Edge, Toggle, Counter, Delay), constants, and vector ops. Building anything beyond a single-tick reactive expression is painful: you can't write `if cond then A else B` for arbitrary types, can't pick one of several values by index, can't store a value across ticks, can't smooth a noisy float, can't run a PID loop.

After this work the user should be able to express, in the editor:

- "Pick burner signal from one of three preset profiles based on a control-stick selector."
- "Latch the last RPM reading when the kill-switch fires."
- "Smooth a stick input into a propeller target RPM with a low-pass filter."
- "Run a PID loop: setpoint = throttle, measurement = current RPM, output = burner signal."

## Architecture

### Type system

#### `PinType.ANY`

New enum value at the end of `PinType`. A pin declared `ANY`:

- Connects to any other pin regardless of declared type — the wire-connect UI never rejects it.
- At evaluator time receives whatever `PinValue` flowed in, **unconverted**, because there's no target type to convert to.
- For outputs: the value is whatever the node emits, type chosen by the node's evaluator.

`ANY` pins do **not** carry type-inference. A node's ANY pins are independent: connecting a Float to `then` does NOT force `else` to also be Float. Reason: type inference across a node graph is a substantial implementation burden, and the alternative (just letting evaluator decide what to do when types differ at runtime) is good enough. In practice the user wires same-type values to both `then` and `else`; if they don't, the evaluator picks the `then` type and converts `else` (using the conversion table below).

#### Implicit conversion table

`PinValueConversion.convert(value: PinValue, target: PinType): PinValue` — single entry point. Returns the converted value, or `PinValue.default(target)` if conversion is not defined.

| From \ To      | BOOL          | INT             | FLOAT             | REDSTONE                | STRING        |
| -------------- | ------------- | --------------- | ----------------- | ----------------------- | ------------- |
| **BOOL**       | identity      | `1` / `0`       | `1.0` / `0.0`     | `15` / `0`              | `"true"` / `"false"` |
| **INT**        | `value ≠ 0`   | identity        | `value.toFloat()` | `value.coerceIn(0, 15)` | `value.toString()` |
| **FLOAT**      | `value ≠ 0`   | `value.toInt()` (truncates toward 0) | identity | `value.toInt().coerceIn(0, 15)` | `"%.3f".format(value)` |
| **REDSTONE**   | `value > 0`   | identity        | `value.toFloat()` | identity                | `value.toString()` |
| **STRING**     | parse, default `false` | parse, default 0 | parse, default 0.0 | parse + clamp, default 0 | identity |

VEC2 / VEC3 / QUAT: NO implicit conversion to or from scalars (lossy, error-prone). User must use `Vec Make` / `Vec Split` explicitly. Vector → ANY: pass-through. ANY → Vector: convert only if `value` is already that vector type, else `PinValue.default(target)`.

Conversion is applied at edge-read time inside the evaluator: when a node reads from an incoming edge, the framework looks up the from-pin's runtime `PinValue` and converts it to the target pin's declared type. If the target is `ANY`, no conversion; the raw `PinValue` flows through.

#### Wire-connect rule

A wire from output `O` to input `I` is allowed iff:

1. `O.type == I.type`, OR
2. `O.type == ANY` or `I.type == ANY`, OR
3. The conversion `O.type → I.type` is defined in the table above (i.e. not the `default` fallback path).

The UI shows during wire-drag hover whether the connection is allowed. If it's an auto-conversion (case 3), a small tooltip label reads `auto: float → int`. Rejected connections show a red ring on the target pin.

#### Internal API

```kotlin
// dev.nitka.nodewire.graph.PinValueConversion
object PinValueConversion {
    fun canConvert(from: PinType, to: PinType): Boolean
    fun convert(value: PinValue, target: PinType): PinValue
}
```

`canConvert` is used by the connect-UI for the hover preview. `convert` runs at every edge read; the cost is one switch + one allocation worst case, negligible against the per-tick eval cost.

`PinValueConversion` is the **only** code that knows the table — node evaluators never special-case "is this an int that needs to be a float" themselves.

### New nodes

Each node listed below has: pin set, config schema, evaluator semantics, where it slots in the registry. Stateful nodes get a `transient state` field on the BE-side evaluator (same pattern as existing Counter / Toggle).

#### FLOW category

##### `if_then_else`
- Inputs: `cond: BOOL`, `then: ANY`, `else: ANY`
- Outputs: `out: ANY`
- Config: none
- Eval: `out = if (cond) then else else_`. The framework passes both then/else `PinValue`s as-is; the evaluator just picks one. No type coercion between then/else — if the caller wired mismatched types, that's their problem (whichever branch fires wins).

##### `switch`
- Inputs: `index: INT`, `case_0: ANY`, `case_1: ANY`, … `case_N-1: ANY`
- Outputs: `out: ANY`
- Config: `cases: Int` (range 2..8, default 4) — drives the pin shape via the existing `EditorState.changeVecOp`-style mutator (rename to a generic `reshapeNodePins`).
- Eval: `out = inputs["case_$index"] ?: PinValue.default(BOOL)`. Out-of-range → empty PinValue (downstream gets the conversion-table default for its expected type).

##### `sample_hold`
- Inputs: `value: ANY`, `trigger: BOOL`
- Outputs: `out: ANY`
- Config: none
- State: `lastHeld: PinValue? = null`, `lastTrigger: Boolean = false`
- Eval: on rising-edge of `trigger` (was false last tick, true now), `lastHeld = value`. Always emit `lastHeld ?: PinValue.default(BOOL)`.

##### `latch_sr`
- Inputs: `set: BOOL`, `reset: BOOL`
- Outputs: `out: BOOL`
- Config: none
- State: `value: Boolean = false`
- Eval: if `reset` → `value = false`; else if `set` → `value = true`; emit `value`. Reset dominates.

##### `latch_d`
- Inputs: `data: ANY`, `clock: BOOL`
- Outputs: `out: ANY`
- Config: none
- State: `value: PinValue? = null`, `lastClock: Boolean = false`
- Eval: on rising-edge of `clock`, `value = data`. Always emit `value ?: PinValue.default(BOOL)`.

##### `sequencer`
- Inputs: `advance: BOOL`, `reset: BOOL`
- Outputs: `step: INT`
- Config: `steps: Int` (range 2..16, default 4)
- State: `step: Int = 0`, `lastAdvance: Boolean = false`
- Eval: if `reset` → `step = 0`; else if rising-edge of `advance` → `step = (step + 1) mod steps`. Emit `step`.

#### MATH category

##### `clamp`
- Inputs: `value: FLOAT`, `min: FLOAT`, `max: FLOAT`
- Outputs: `out: FLOAT`
- Config: none
- Eval: `out = value.coerceIn(min, max)`. If `min > max` swap them at evaluator level (silent fix; alternative would be to emit garbage on misconfig).

##### `map`
- Inputs: `value: FLOAT`, `from_min: FLOAT`, `from_max: FLOAT`, `to_min: FLOAT`, `to_max: FLOAT`
- Outputs: `out: FLOAT`
- Config: none
- Eval: `out = to_min + (value - from_min) * (to_max - to_min) / (from_max - from_min)`. If `from_max == from_min` → emit `to_min` (avoid div-by-zero). Result is NOT clamped — user can chain `clamp` if they want.

##### `lerp`
- Inputs: `a: FLOAT`, `b: FLOAT`, `t: FLOAT`
- Outputs: `out: FLOAT`
- Config: none
- Eval: `t = t.coerceIn(0f, 1f); out = a + (b - a) * t`.

##### `smooth`
- Inputs: `target: FLOAT`, `factor: FLOAT`
- Outputs: `out: FLOAT`
- Config: none
- State: `current: Float = 0f` (initialised on first tick to `target` so we don't ramp up from zero)
- Eval: `current = current + (target - current) * factor.coerceIn(0f, 1f); out = current`. `factor` close to 0 = very slow smoothing, 1 = no smoothing (passthrough).

##### `pid`
- Inputs: `setpoint: FLOAT`, `measurement: FLOAT`, `kp: FLOAT`, `ki: FLOAT`, `kd: FLOAT`
- Outputs: `out: FLOAT`
- Config: `i_min: Float = -1000f`, `i_max: Float = 1000f` (integral clamp — prevents wind-up)
- State: `integral: Float = 0f`, `lastError: Float = 0f`
- Eval:
  ```
  error = setpoint - measurement
  integral = (integral + error).coerceIn(i_min, i_max)
  derivative = error - lastError
  lastError = error
  out = kp * error + ki * integral + kd * derivative
  ```
  Time step is implicit (one tick = 50ms = 1 unit) — user tunes ki/kd accordingly.

### UI

#### ANY-pin rendering

`PinType.ANY` gets its own colour in `pinColors`: `NwTheme.colors.pinAny` (neutral gray, e.g. `#9CA3AF`). The pin dot draws the same size as other pin dots — only the colour changes.

Pin label: `(any)` rendered in `NwTheme.colors.onSurfaceMuted` italics. Existing pin labels stay un-italicised so the ANY label is visually distinct.

When a wire is already attached to an `ANY` pin, the pin dot border (1 px) takes on the colour of the **resolved** type (the type of the value flowing through, sampled from the last eval result). The dot center stays gray — gives the user a quick visual confirmation of "this is an any-pin currently carrying float / bool".

#### Wire-drag feedback

While dragging a wire from one pin to another, the target pin's hover state is one of:

- **Green ring** — types match exactly or one end is ANY. Tooltip: `connect`.
- **Amber ring** — implicit conversion required. Tooltip: `auto: <from> → <to>`.
- **Red ring** — incompatible. Tooltip: `cannot connect <from> to <to>`.

Implemented by extending the existing `wireDragSource` snapshot logic in `WireLayer`. Cost: one `PinValueConversion.canConvert` call per hover-tick — trivial.

#### Config UIs

- `switch.cases` (Int 2..8): a numeric stepper at the bottom of the config sheet. Same pattern as `vec_op.dim`.
- `sequencer.steps` (Int 2..16): numeric stepper.
- `pid.i_min` / `i_max`: two FLOAT inputs. Used only by advanced users; collapse into a "show advanced" disclosure to keep the default config sheet small.

All other new nodes have **no config** — they're pure functions of their inputs.

### Evaluator integration

The framework's evaluator already calls each node's evaluator function with `(config, inputs) -> outputs`. The auto-conversion runs at the **edge-read step**, not inside individual evaluators:

```kotlin
// in GraphEvaluator.evaluate(...)
val inputs = node.inputs.associate { inputPin ->
    val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == inputPin.id }
    val rawValue = edge?.let { outputs[it.from.node to it.from.pin] }
        ?: return@associate inputPin.id to PinValue.default(inputPin.type)
    val converted = if (inputPin.type == PinType.ANY) rawValue
                    else PinValueConversion.convert(rawValue, inputPin.type)
    inputPin.id to converted
}
```

This is the **only** integration point that needs changing for auto-conversion. Existing nodes are unaffected (they read pre-typed values).

### Stateful nodes integration

The existing `StatefulGraphEvaluator` already holds per-node state across ticks (Counter, Toggle, etc. use it). Each new stateful node adds a `state class` on the evaluator side. Pattern:

```kotlin
class SampleHoldState {
    var lastHeld: PinValue? = null
    var lastTrigger: Boolean = false
}
```

`StatefulGraphEvaluator` looks up `(nodeId, stateClass)` and provides it to the evaluator. Same pattern as existing nodes — no framework change.

### Node-pin reshape (Switch / Sequencer)

`Switch` needs N case pins driven by config. `Sequencer` config drives modulo arithmetic but doesn't reshape pins. The reshape logic already exists for `vec_op` (changing dim 2↔3 reshapes its inputs) and `aero_input` (channel change reshapes pins). We extract a small helper `EditorState.reshapeNodePins(nodeId, newInputs, newOutputs)` and reuse from `switch.changeCases` and elsewhere.

## Versioning + back-compat

This is a **minor** bump: v0.3.0. The codec is back-compatible:

- `PinType.ANY` is a new enum value. Saved graphs that don't reference it are unaffected. Saved graphs that DO reference it (impossible until v0.3.0 ships) load fine on v0.3.0+.
- Implicit conversion changes the runtime behaviour of edges between mismatched types. Previously such edges were **forbidden by the connect-UI** — so no saved graph contains them. Old graphs continue to work bit-identically.
- New node types: same convention as Aero (mod-list-aware loading). Saved graphs that use a new node load fine on v0.3.0; on v0.2.x they fail with "unknown node type" — no silent corruption.

## Testing

| Test class                       | Verifies |
| -------------------------------- | -------- |
| `PinValueConversionTest`         | Every (from, to) pair in the table produces the expected value. Lossy cases (FLOAT→INT truncation, REDSTONE OOR clamp) verified. Default-fallback on incompatible types (e.g. VEC3→INT) returns `PinValue.default(target)`. |
| `WireCompatibilityTest`          | `canConvert` returns true/false for every pair. ANY accepts everything. Identity pairs always true. |
| `IfThenElseTest`                 | Pure-functional: cond true → then, cond false → else. Works for BOOL / FLOAT / VEC3. |
| `SwitchTest`                     | index 0..N-1 → corresponding case. Out-of-range → default. Reshape on config change. |
| `SampleHoldTest`                 | Holds initial null; on rising trigger captures value; subsequent ticks emit held value; falling trigger does NOT capture. |
| `LatchSrTest`                    | set→true, reset→false, both→reset wins, neither→hold. |
| `LatchDTest`                     | Rising clock captures, falling does not, holds across ticks. |
| `SequencerTest`                  | Advances on rising-edge, wraps mod N, reset returns to 0. |
| `SmoothTest`                     | Multi-tick: converges to target with factor=0.1, instant with factor=1.0, holds at 0 with factor=0.0. |
| `PidTest`                        | Step response: P-only converges to bias proportional to error; P+I converges to zero error eventually; integral clamps at i_max. |
| `ClampTest`, `MapTest`, `LerpTest` | Boundary cases (value < min, > max, = min; from_min == from_max edge case for map). |

Manual in-client: place block, build a graph using Switch + Sample-Hold + PID + Lerp, save/load, verify everything reconnects with the right pin shapes. Try connecting INT to FLOAT — wire goes green, value passes through as Float. Try VEC3 to BOOL — wire stays red.

## Out of scope

- **Full state machines** with arbitrary transition matrices. `Sequencer` is the simplified version; user can build richer state logic by combining it with `IfThenElse` + `Latch`.
- **Trig / pow / log / exp** in `Math` node — easy to add later as additional `MathOp` enum values; deferred to a follow-up because they're not blocking algorithm building.
- **Hysteresis / Schmitt trigger** — can be built from `Compare` + `LatchSR` once we have these. Defer until users hit it.
- **Type inference across ANY pins on the same node** — explicitly rejected during brainstorming; ad-hoc `convert` at edge-read time is good enough.

## Scope sizing

~16 implementation tasks across 3 phases:

1. **Type system** (5 tasks) — `PinType.ANY` enum value, `PinValueConversion` + test, edge-read integration in `GraphEvaluator`, wire-connect UI rules, ANY-pin rendering.
2. **Pure nodes** (5 tasks) — `if_then_else`, `switch` (incl. reshape), `clamp`, `map`, `lerp`.
3. **Stateful nodes** (6 tasks) — `sample_hold`, `latch_sr`, `latch_d`, `sequencer`, `smooth`, `pid` — each with its own state class and test.

Plan execution: subagent-driven, same flow as the CC integration. Each task is a self-contained TDD cycle.
