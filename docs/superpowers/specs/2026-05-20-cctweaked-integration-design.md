# CC: Tweaked Integration — Design

**Date:** 2026-05-20
**Scope:** Expose Nodewire's named channels to CC: Tweaked computers as a peripheral. Read graph output, write graph input, react to changes via events, introspect the graph.

## Goal

Place a Nodewire block, attach a CC computer (wired modem adjacency or direct neighbour), and from Lua:

- read the current value of any `Channel Output` node by name,
- write a new value to any `Channel Input` node by name,
- receive a `nodewire_channel` event whenever an output channel changes,
- introspect the graph topology (nodes, edges).

The integration is optional. With CC: Tweaked absent, the mod compiles and runs unchanged — the integration module simply doesn't register anything.

## Architecture

### Module layout

```
src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/
├── NodewirePeripheral.kt        # IPeripheral implementation
├── NwChannelLuaCodec.kt         # PinValue ↔ Lua Object
├── NwChannelIntrospection.kt    # graph → (inputs, outputs) maps
├── NwGraphIntrospection.kt      # graph → nodes[] / edges[] for getNodes/getEdges
├── NwPeripheralCapability.kt    # BlockCapability registration
└── NwChannelEventDispatch.kt    # serverTick diff → queueEvent on attached computers
```

All files in this package are gated by `ModList.get().isLoaded("computercraft")` at the registration site (`Nodewire.init`). The classes themselves reference CC API symbols directly, so they MUST NOT be class-initialized unless CC is present.

### Hook points in existing code

- `LogicBlockEntity.externalChannelInputs: MutableMap<String, PinValue>` — already drives runtime channel feeds from other Nodewire blocks. CC `setChannel` writes here.
- `LogicBlockEntity.serverTick` — already evaluates and stores results. After eval, the new `NwChannelEventDispatch.diffAndBroadcast(be, prevSnapshot, newSnapshot)` runs. Snapshot of last channel-output values lives on the BE.
- `Nodewire.init` — adds a `ModList`-guarded call to `NwPeripheralCapability.register()`.

No mutation of existing files beyond:

1. `LogicBlockEntity` — gain a `lastChannelOutputSnapshot: Map<String, PinValue>` field (private). Updated end-of-tick.
2. `Nodewire.init` — add the CC capability-registration call.
3. `build.gradle.kts` — add CC API as `compileOnly` + CC NeoForge as `runtimeOnly`. Add an explicit dependency on CC in `neoforge.mods.toml` is NOT required (we work even without CC).

### Data flow

```
Lua: peripheral.setChannel("enable", true)
  → NodewirePeripheral.setChannel
    → resolve name → ChannelInput node id + PinType
    → NwChannelLuaCodec.encodeOrThrow(luaValue, expectedType) → PinValue
    → BE.externalChannelInputs["enable"] = PinValue.Bool(true)
    → BE.setChanged() — invalidates evaluator, marks BE dirty

(next server tick)
BE.serverTick:
  externalOutputs = collectChannelInputExternals(BE.externalChannelInputs, graph)
  result = evaluator.tick(externalOutputs)
  newSnapshot = NwChannelIntrospection.outputSnapshot(graph, result)
  NwChannelEventDispatch.diffAndBroadcast(be, prevSnapshot = lastChannelOutputSnapshot, newSnapshot)
  lastChannelOutputSnapshot = newSnapshot
```

### Capability

NeoForge 1.21.1 + CC: Tweaked uses block-capability registration:

```kotlin
event.registerBlockEntity(
    Capabilities.Peripheral.BLOCK,        // dan200.computercraft.api.peripheral.PeripheralCapability.get()
    Registry.LOGIC_BLOCK_ENTITY.get(),    // our BE type
) { be, side -> NodewirePeripheral(be, side) }
```

The factory may return the same instance across calls for a given (BE, side) pair if convenient; CC: Tweaked compares by `equals`, so `NodewirePeripheral.equals` is implemented on `(be, side)` identity.

### Attach lifecycle

`IPeripheral.attach(computer)` is called once per computer that connects. `detach(computer)` is called on disconnect, BE removal, or wired-network reshuffles. The peripheral tracks attached computers in a `WeakHashMap<IComputerAccess, Unit>` so a forgotten detach doesn't pin a computer reference. Event broadcast iterates this set.

## Lua API surface

```lua
local nw = peripheral.find("nodewire")
```

### Reads

| Method                            | Returns                                                                     | Notes |
| --------------------------------- | --------------------------------------------------------------------------- | ----- |
| `nw.getChannel(name)`             | `boolean \| number \| string \| table \| nil`                               | `nil` when no `Channel Output` by that name exists. |
| `nw.listChannels()`               | `{ inputs = { [name]=type, ... }, outputs = { [name]=type, ... } }`         | `type` is the lowercased `PinType.name` (`"bool"`, `"vec3"`, …). Blank-named channels are excluded. |
| `nw.getNodes()`                   | array of `{ id, type, label, inputs = {{id,name,type},…}, outputs = {…} }`  | `id` is the node UUID as string. `label` is the user-set label or `nil`. |
| `nw.getEdges()`                   | array of `{ from = { node, pin }, to = { node, pin }, label }`              | `label` is the wire label string or `nil`. |

### Writes

| Method                            | Returns                                                                     | Notes |
| --------------------------------- | --------------------------------------------------------------------------- | ----- |
| `nw.setChannel(name, value)`      | `true`                                                                      | Throws Lua error `"no such channel"` if name doesn't match a `Channel Input` or `"type mismatch: expected X"` if value can't be encoded. |

### Events

| Event name           | Args            | When |
| -------------------- | --------------- | ---- |
| `nodewire_channel`   | `name, value`   | Fired on each attached computer when a `Channel Output` value changes between server ticks. First tick after attach also fires for every named channel (so Lua doesn't have to seed initial state). |

## Value codec (`NwChannelLuaCodec`)

PinValue ↔ Lua bridge. Reading converts a `PinValue` to a Lua-safe Java object; writing parses a Lua-supplied Object into a typed `PinValue` (or throws).

| PinType  | Lua representation                              |
| -------- | ----------------------------------------------- |
| BOOL     | `boolean`                                       |
| INT      | `number` (must be integral; floor on accept)    |
| FLOAT    | `number`                                        |
| REDSTONE | `number` in `0..15` (clamped)                   |
| STRING   | `string`                                        |
| VEC2     | `{ x = number, y = number }`                    |
| VEC3     | `{ x = number, y = number, z = number }`        |
| QUAT     | `{ x = number, y = number, z = number, w = number }` |

Decode rejects with `LuaException("type mismatch: expected <type>")` on:
- wrong primitive class (e.g. string passed for INT),
- missing required field (e.g. `z` absent on VEC3),
- non-finite numbers (NaN / Infinity).

## Channel introspection

`NwChannelIntrospection.inputs(graph)` and `.outputs(graph)` walk `graph.nodes`:

- inputs: every node of `typeKey == channel_input` with non-blank `config["name"]`. Maps name → `PinType.fromName(config["type"])`. On duplicate names the **first** by creation order wins; the rest are skipped (logged as warning once per BE per game session).
- outputs: same shape, but for `channel_output`.

The graph never references nodes by name at runtime, so this is purely a lookup table built fresh each call. Cost is O(N) over `graph.nodes`; CC reads are infrequent.

## Event dispatch

`NwChannelEventDispatch.diffAndBroadcast(be, prev, new)`:

1. For every `name` in `new`:
   - if `prev[name]` is missing OR `prev[name] != new[name]`, emit.
2. For every `name` removed from the graph (`prev` has it, `new` doesn't) — emit `nodewire_channel(name, nil)` so Lua can clean up.
3. Each emission iterates attached computers (snapshot of the set first to avoid concurrent modification during attach/detach) and calls `computer.queueEvent("nodewire_channel", name, codec.toLua(value))`.

First-tick-after-attach behaviour: when `attach(computer)` runs, it stages a "send all current outputs as events on first eligible diff call". The BE serves this by passing an empty `prev` for that computer on the next tick. Implementation: per-attach flag `needsInitialSync` flipped on attach, cleared on first successful diff.

## ModList guard

```kotlin
// integration/cctweaked/NwPeripheralCapability.kt
internal val CCTWEAKED_LOADED: Boolean by lazy {
    net.neoforged.fml.ModList.get().isLoaded("computercraft")
}
```

Used at three sites:

1. `Nodewire.init` — skip the entire `register()` call when absent. No CC API class is touched.
2. `LogicBlockEntity.serverTick` — skip the snapshot/diff/broadcast block. The `lastChannelOutputSnapshot` field stays empty; no cost paid.
3. `BindAeroSourcePacket` and similar code paths don't need awareness — CC is orthogonal.

The peripheral classes themselves directly import CC API. If CC is absent, those classes are simply never loaded (JVM lazy class init).

## Build

`build.gradle.kts`:

```kotlin
// CC: Tweaked NeoForge for 1.21.1. API compileOnly + impl runtimeOnly,
// matching the pattern for Create / Aeronautics / Tweaked Controllers.
compileOnly("cc.tweaked:cc-tweaked-${mcVer}-neoforge-api:1.115.1")
runtimeOnly("cc.tweaked:cc-tweaked-${mcVer}-neoforge:1.115.1")
```

The exact version (`1.115.1`) will be validated by `./gradlew dependencies` during Task 1 of the plan; if Modrinth or Maven has a newer 1.21.1-compatible release we'll bump it there.

## Testing

| Test class                                  | Verifies |
| ------------------------------------------- | -------- |
| `NwChannelIntrospectionTest`                | Mixed named/unnamed channel nodes → correct `inputs`/`outputs` maps; duplicate names → first wins; blank names skipped. |
| `NwChannelLuaCodecTest`                     | Round-trip for every `PinType`. Wrong-class input throws `LuaException` with expected message. NaN/Infinity rejected. |
| `NwSetChannelTypeMismatchTest`              | `setChannel` with wrong-type Lua value → propagates `LuaException`; BE state untouched. |
| `NwEventDiffTest`                           | Identical snapshots → no events. One channel changed → one event with `(name, value)`. Channel removed → one event with `(name, nil)`. First-tick-after-attach → events for every output. |

All four tests run as plain JUnit 5 in `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/`. They do not require CC at compile time — the codec test mocks `LuaException` (the test classpath gets the CC API jar as a test dependency only). The peripheral test uses a small fake `IComputerAccess` recording `queueEvent` calls.

Manual in-client verification covers: attach modem to a Nodewire block, run a Lua script that calls each API method, verify event firing on graph change.

## Non-goals

- No CC peripheral on Nodewire **wires** — wires aren't blocks.
- No CC API for the redstone-link slots — those are Create-specific, computer can already drive a Create redstone link directly.
- No remote graph editing from Lua — `getNodes`/`getEdges` is read-only; mutation goes through the existing in-world editor.
- No support for `redstone_link_input` / `redstone_link_output` via this peripheral — out of scope.

## Scope sizing

~11 implementation tasks across 4 phases:

1. **Foundation** — build deps, ModList helper, value codec + test.
2. **Introspection** — channel maps, graph maps; tests.
3. **Peripheral** — IPeripheral methods (`getChannel`, `setChannel`, `listChannels`, `getNodes`, `getEdges`), attach/detach.
4. **Wiring** — capability registration, snapshot + event diff in `serverTick`, ModList guard, manual verification.

Comparable size to the Aeronautics integration completed earlier in this branch.
