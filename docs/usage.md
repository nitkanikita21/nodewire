# Usage

Short reference for everyday use. For build / contributor info see the main [`README`](../README.md).

## Blocks & items

| Block / item | Role |
| --- | --- |
| **Logic Block** | Carries a node graph; evaluates every server tick. Right-click to open the editor. |
| **Screen** | Displays a VIDEO channel on its face. Merges into multiblock panels; doubles as a touch source. |
| **Camera** | Produces a VIDEO handle — a live capture of the world from its face. Has `fov`/`enable`/`yaw`/`pitch` input pins. |
| **Channel Link Tool** | THE wiring tool: links pins between blocks, manages bindings, resizes screen panels. |

## Channel Link Tool — unified pin linking

Every linkable block exposes **pins** (typed, named values it produces or accepts).
One flow links anything to anything:

1. **Sneak + RMB** a block → its **output pins**. One pin arms instantly, several open a picker.
   (A Logic Block opens the **Link Manager** instead — its channel list + existing bindings, scrollable.)
2. **RMB** another block → its **input pins**, filtered to types the armed source can convert into.
   One pin commits instantly, several open a picker.

Who has pins:

- **Logic Block** — every named `Channel Output` node is an output pin, every `Channel Input` an input pin.
- **Screen** — out: `touch` (VEC2, last tap in panel px), `touch_down` (BOOL, 1-tick pulse); in: `screen` (VIDEO).
- **Camera** — out: `video` (VIDEO); in: `fov` (FLOAT 30–110), `enable` (BOOL), `yaw`/`pitch` (FLOAT).
- **Aeronautics blocks** — the full per-kind channel catalog (~33 channels) as output pins.
- **CBC Cannon Mount** — out: `cannon yaw`, `cannon pitch`, `mount position` (VEC3), `mount position (text)` (STRING).
- **Containers** (anything with item/fluid capabilities or a comparator signal) — sensor readings
  (`ITEM_COUNT`, `FLUID_FILL`, `COMPARATOR`, `IS_EMPTY`, …) as output pins.
- **Any other block** — fallback pins: output `redstone` (its best-neighbour signal),
  input `redstone <face>` (drive-by-wire onto that face, works at any distance).

Links are stored on the **consuming** block and pulled every server tick — re-delivered continuously,
pruned automatically when an endpoint disappears, and the fed value resets when a link is removed.
Endpoints are Sable-aware: blocks on an assembled structure keep working and report **world-space** positions.

Manage links: sneak+RMB a Logic Block → **Link Manager** — "Incoming links" section lists every pin link
with a ✕ to remove and ◎ to highlight the source block in the world. Wires render in-world while the tool is held.

**Tool modes** (sneak+scroll): **Link pins** ↔ **Screen panels**.

## Screens, video, touch

- **Bind a camera**: sneak+RMB camera (`video` arms) → RMB screen (`screen`). The handle re-delivers every tick.
- **Multiblock panels**: tool in *Screen panels* mode → sneak+RMB two opposite corners (same facing, ≤ 8×8).
  The panel acts as ONE screen: bind video to any cell, taps land anywhere, breaking any block dissolves it.
  Rectangular panels get a matching surface aspect automatically.
- **Touch**: any tap on a panel (empty hand or items; the Link Tool is excluded) is recorded.
  Pull it into logic by linking the screen's `touch`/`touch_down` pins into VEC2/BOOL channels.
- **VIDEO channels** carry only a UUID handle — never frames. Scripts can consume, transform and emit
  video (`input<Video>` / `output<Video>` + `draw {}`), so one camera can feed several screens with
  different overlays, including across Logic Blocks.

## Opening the editor

Place a **Logic Block** and right-click it. The graph saves to the server **when the editor closes**.

While the editor is open (singleplayer), `Channel Input` chips and everything downstream show **live
server-delivered values** — what you see is what the block computes.

## Editing workflow

1. **Right-click the canvas** → `Add node ▸ <category>`, `Add Comment`, `Insert group ▸ …`.
2. **Drag from an output pin** to an input pin to draw a wire. Pin colours = value type.
3. **Inline pin editors** on the card set defaults for unconnected inputs (numbers, text, vectors,
   checkboxes, dropdowns). **Press Enter to commit** text/number fields; a connected wire overrides the default.
4. **Toolbar**: `File ▸ Save / Save as / Open / New`, `Edit ▸ Undo / Redo`, `View ▸ Frame …`, Link Manager.
5. Cards size themselves to their pin content; pin-heavy nodes (scripts) grow up to 320 px wide.

Graphs are saved per client under `<gamedir>/nodewire-graphs/<name>.snbt`, group templates under
`<gamedir>/nodewire-groups/`, scripts under `<gamedir>/nodewire-scripts/`.

## Keybinds

Active when the editor is focused **and** no text field has focus.

| Shortcut             | Action                                  |
| -------------------- | --------------------------------------- |
| `Del` / `Backspace`  | Delete selected nodes / groups / comments |
| `Ctrl+A`             | Select all                              |
| `Ctrl+D`             | Duplicate selection (groups flatten to member-nodes) |
| `Ctrl+C` / `Ctrl+X`  | Copy / cut selection                    |
| `Ctrl+V`             | Paste at cursor                         |
| `Ctrl+Z`             | Undo                                    |
| `Ctrl+Shift+Z` / `Ctrl+Y` | Redo                               |
| `F` / `Shift+F`      | Frame selection / frame all             |
| `Ctrl+G` / `Ctrl+Shift+G` | Group selection / ungroup          |
| `Esc`                | Cancel rename → close menu → clear selection → close (saves) |
| Double-LMB on header | Rename node / group inline              |
| Drag on empty canvas | Marquee — selects nodes + groups + comments (Shift = additive) |

## Node categories

- **IO** — `Side Input` / `Side Output` (redstone on a block face), `Channel Input` / `Channel Output`
  (named channels — these ARE the block's link pins), `Redstone Link Input` / `Output` (Create wireless),
  `Block Sensor` (capability readings), `Aeronautics Input`, `Controller Input`, `Script`.
- **Logic** — `Logic Gate` (AND / OR / NOT / XOR / NAND / NOR / XNOR).
- **Math** — `Math`, `Compare`, `Clamp`, `Map` (range remap), `Lerp`, `Smooth` (low-pass), `PID`.
- **Conversion** — `Convert` between bool / redstone / int / float.
- **Flow** — `Select Bool`, `Rising Edge`, `Toggle`, `Counter`, `Delay`, `If Then Else`, `Switch`,
  `Sample & Hold`, `Latch SR`, `Latch D`, `Sequencer`.
- **Constants** — `Constant` (any type incl. vectors and strings), `Timer`, `Pulse`, `Random Bool`, `Random Int`.
- **Vector** — `Vec Make`, `Vec Split`, `Vec Op` (add/sub/dot/cross/normalize/rotate/…).
  Vector values are **double precision** end to end — world coordinates survive the pin boundary.
- **Groups** — visual containers: collapse to a tile, save as template, live-edit.
- **Comments** — floating text boxes, not evaluated.

**`any` pins** accept any type; mismatched scalars auto-convert at evaluation. Vectors/quaternions never
auto-convert to scalars — use `Vec Make` / `Vec Split`.

## Script node

A `Script` node runs a **Kotlin script** (`*.nw.kts`) compiled in-game (the optional
`nodewire_scripting` addon carries the compiler; without it script nodes show a diagnostic and output defaults).

```kotlin
val dist = input<Float>("dist")          // becomes an input pin
val elev = output<Float>("elevation")    // becomes an output pin
var n by state(0)                        // persisted per-node state

tick {                                   // runs once per server tick
    n += 1
    elev.value = dist.value * 0.5f
    if (n % 100 == 0) log("alive")       // log() → console, chat() → nearby players
}
```

- **Editor**: line numbers, scrolling, undo/redo, auto-indent; `Open…` / `Save…` use **native file dialogs**
  (`<gamedir>/nodewire-scripts/`). Closing the editor (any way) commits the source.
- **Pins** reshape from the declared `input<T>` / `output<T>` calls. Supported types: `Boolean`, `Int`,
  `Float`, `Redstone`, `String`, `Vec2`, `Vec3`, `Quat`, `Video`.
- **State**: `var x by state(initial)` persists in the node's NBT (Int/Float/Boolean/String/Redstone/Video).
- **Compile status** shows as a badge on the card; errors appear in the editor.
- The sandbox allows `kotlin.*` (safe subset), `java.lang` basics, `org.joml.*` and the script API —
  no IO, no reflection, no threads. Runaway bodies are disabled, the server tick never blocks.

### Math in scripts

Pins carry plain immutable `Vec2`/`Vec3`/`Quat` (doubles). **JOML is fully available** (default-imported):

```kotlin
val d = target.value.toJoml().sub(gun.value.toJoml(), Vector3d())   // exact at world scale
aim.value = d.normalize().toVec3()
```

`toJoml()` → `Vector2d`/`Vector3d`/`Quaterniond`; `toVec2()/toVec3()/toQuat()` convert back
(float variants like `Vector3f` convert too).

### Video in scripts

```kotlin
val cam = input<Video>("cam")
val out = output<Video>("out")
tick {
    draw(out) {                       // runs on the client render thread
        image(cam.value)              // blit another channel
        line(10, 10, 100, 100, 0xFF00FF66)
        text("HUD", 6, 6, 0xFFFFFFFF)
        ui(pad = 8) { row { text("flex"); spacer(); text("layout") } }   // flexbox DSL
        project(cam.value, x, y, z)?.let { p -> border(p.x.toInt() - 8, p.y.toInt() - 8, 16, 16, 1, 0xFFFF0000) }
    }
}
```

`project()` maps a **world position** to pixel coordinates on a camera feed — outline mobs, mark targets.

### CBC ballistics in scripts

With Create Big Cannons installed, scripts get a live `Cbc` catalog — read from CBC's data-driven
munition registry at runtime, so **addon and datapack projectiles are included automatically**:

```kotlin
val shell = Cbc.shell("he_shell")!!                       // or "cbcmoreshells:shelless_he_shell"
val v0 = Cbc.muzzleVelocity(shell, charges = 8.0)          // 4 powder charges; dual-cannon shells
                                                           // carry shell.initialVelocity instead
val sol = Cbc.solvePitch(shell, v0, dx = 250.0, dy = -5.0, highArc = false)
// sol.pitchDeg, sol.flightTicks, sol.impactSpeed; Cbc.maxRange(...) for the envelope
```

The solver replays CBC's exact per-tick integrator (gravity + drag, linear or quadratic). Heavy —
cache the solution and re-solve a few times per second, not every tick.

### Ready-made scripts (`<gamedir>/nodewire-scripts/`)

- **`cbc_fire_control.nw.kts`** — full fire-by-coordinates computer: target in, yaw+pitch solution out,
  aim errors for the traverse/elevation drives, quantized drive speeds (gearshift + Synaxis resistor),
  lock gate, fire output. Header comments document every pin and the wiring.
- **`cbc_gunner.nw.kts`** — camera gunsight: crosshair overlay, touch-driven range dial, READY gate
  against the mount's real pitch.

## Integrations

All integrations are mod-gated — Nodewire works without any of them.

### Sable

Blocks on a Sable sub-level keep evaluating across the boundary. Endpoint references use a
`(subLevelId, BlockPos)` payload and survive motion; world-space queries apply `logicalPose()`
server-side and `renderPose()` client-side. Cameras, screens, pin links and the editor all work
on assembled structures.

### Create Aeronautics

`Aeronautics Input` node (pick block kind + channel) **or** link any Aero block's pins directly with
the Link Tool. Supported kinds: propellers, Hot-Air Burner, Steam Vent, Mounted Potato Cannon,
Propeller Bearing and more — ~33 channels.

### Create Big Cannons

- **Cannon Mount pins**: `cannon yaw` / `cannon pitch` (live barrel orientation), `mount position`
  (VEC3, world-space, Sable-aware), `mount position (text)` (STRING, full precision for fire-control parsing).
- **Script ballistics**: the `Cbc` object (see above).
- Fire a mount by linking a BOOL channel into its face (`redstone <face>`).

### Create

`Redstone Link Input` / `Output` nodes (wireless redstone). Frequencies use Create's two-item-slot
model; the slots accept JEI / EMI ghost ingredients.

### Tweaked Controller

`Controller Input` node — bind a controller, pick a channel: composite sticks (Vec2 or split axes),
buttons (Bool/Redstone), triggers (Float/Redstone/Bool), half-axes. Offline controller = zero values.

### CC: Tweaked

A Logic Block is a **peripheral**: `getChannel(name)`, `setChannel(name, value)`, `listChannels()`,
`getNodes()`, `getEdges()`, plus a `nodewire_channel` event on output changes — drive or observe a
graph from Lua.

### JEI / EMI

Ghost-ingredient handlers for the redstone-link frequency slots — drag straight from the ingredient list.
