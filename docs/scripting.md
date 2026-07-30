# Nodewire Scripting Guide

The **Script node** runs real Kotlin (`*.nw.kts`) compiled *in-game*. This is the full guide:
the API surface, the server/client model, coroutines, state replication, video drawing and a set
of complete example scripts. For a quick tour see [`usage.md → Script node`](usage.md#script-node).

Requires the optional **`nodewire_scripting`** addon (it carries the Kotlin compiler). Without it
script nodes show a diagnostic and output type-defaults — nothing breaks.

---

## 1. Quick start

Add a `Script` node to a Logic Block graph, right-click it, write:

```kotlin
val speed = input<Float>("speed")        // becomes an input pin
val fast  = output<Boolean>("fast")      // becomes an output pin
var peak by state(0f)                    // persisted per-node state (NBT)

tick {                                   // runs once per server tick
    if (speed.value > peak) peak = speed.value
    fast.value = speed.value > 0.8f
}
```

Close the editor — the source commits, compiles **asynchronously off-thread**, pins reshape from
the `input`/`output` declarations, and a status badge appears on the card (`compiling` → `ok` /
`err` with diagnostics). Until the first successful compile the node outputs defaults.

Everything from `dev.nitka.nodewire.script.*`, the `ui` DSL and **`org.joml.*` is default-imported**
— no `import` lines needed for the common surface.

---

## 2. Pins

```kotlin
val a = input<Float>("range", default = 100f)   // default used while disconnected
val b = output<Vec3>("aim")
```

Allowed pin types: `Boolean`, `Int`, `Float`, `Redstone`, `String`, `Vec2`, `Vec3`, `Quat`, `Video`.

- **Disconnected input** = its `default` (or the type's zero value: `false`, `0`, `""`,
  `Redstone.OFF`, zero vectors, `Quat.IDENTITY`, `Video.NONE`). It does **not** keep the last
  wired value.
- **Cross-type coercion** happens on read — wiring an `Int` into `input<Float>` just works
  (same conversion table as node wires; `Boolean → Redstone` gives 15/0).
- `Redstone` is a clamped 0..15 value class: `Redstone(7)`, `.power`, `.isOn`, `Redstone.OFF`,
  `Redstone.MAX`.
- **Pulse inputs**: there is no special pulse API. A pin fed by an *event* source (a screen's
  `touch_down`, a panel button) reads `true` for **exactly one tick** per event and `false`
  otherwise — the link layer latches the event stamp for you. Sticky sources (`touch`,
  toggle state, …) just hold their value.
- **Output writes are batched**: `out.value =` writes a buffer that commits at the next suspend
  point (end of a `tick {}` pass, or `tick()`/`delay()`/`sync {}` in a behavior). Multiple writes
  between suspends collapse to the last one — downstream never sees a torn half-update.
- Downstream nodes see your outputs with **one tick of latency** (evaluator pipeline, not the
  script).

---

## 3. State & persistence

```kotlin
var count by state(0)                       // persisted in the node's NBT
var hud   by state(0f, replicated = true)   // ALSO synced server → client
```

- Allowed state types: `Int`, `Float`, `Boolean`, `String`, `Redstone`, `Video`.
  The **property name is the NBT key** — rename it and the old value is left behind.
- State survives chunk unloads, world restarts and script edits (matching keys reload).
- Behaviors do **not** survive a reload — they restart from the top of the script with the
  restored state values. Design bodies so they can resume from state (see the traffic light
  example).

### Server → client replication

By default state lives **only on the server**. Pass `replicated = true` to mirror a cell to
clients:

- The server sends **per-cell deltas, on change only** (value-equality dirty tracking) to players
  tracking the chunk; late joiners get a filtered snapshot. Non-replicated cells never leave the
  server.
- On the client, reading a **non-replicated** cell throws
  `state 'x' is not replicated — pass replicated = true`.
- Client code also **cannot read input pins** (except `Video`) — inputs exist server-side.
  The pattern is always: *read inputs in `tick {}`, copy what the client needs into replicated
  state, render from state in `clientBehavior {}`*:

```kotlin
val speed = input<Float>("speed")
var shown by state(0f, replicated = true)

tick { shown = speed.value }                 // server: pin → replicated state

clientBehavior {
    while (true) {
        draw(out) { text("SPD %.1f".format(shown), 8, 8, 0xFF_00FF66) }
        frame()                              // client: state → pixels
    }
}
```

---

## 4. Behaviors & coroutines

`tick {}` is sugar. The real execution model is **coroutine behaviors**:

```kotlin
behavior {              // SERVER coroutine — suspend context
    while (true) {
        // ... do work ...
        tick()          // commit outputs, park until the next server tick
    }
}

clientBehavior { ... }  // CLIENT coroutine — parks on the render-frame clock
```

`tick { body }` ≡ `behavior { while (true) { body(); tick() } }`. You can declare **several**
behaviors; within one node they run on a single-slot dispatcher and interleave only at suspend
points, so plain top-level `var`s are safe to share between them (launch order = resume order).
Nothing is shared across nodes or sides.

Suspend primitives (inside `behavior`/`clientBehavior` only):

| Call | Meaning |
|---|---|
| `tick()` | commit outputs, park until the next server tick |
| `delay(n)` / `delay(5.ticks)` | park for *n* ticks |
| `frame()` | park until the next **client** frame (client behaviors) |
| `frameDelay(n)` | park for *n* frames |
| `sync { ... }` | run the block, commit it as **one** atomic frame, park |
| `dt()` | seconds since this behavior's previous resume (≈0.05 on the server; real frame delta on the client; `0` on the first resume) |

### Backpressure & the runaway guard

The game **never waits for your script**. Behaviors run on a worker pool off the game thread:

- If a behavior hasn't parked when the server tick arrives, the tick is **skipped** for that node
  — previous outputs are reused and a *strike* is counted. A slow script just updates less often.
- **200 consecutive** unparked ticks ⇒ the node is disabled ("behavior never yielded (runaway)"),
  outputs drop to defaults. Fix the script and re-save to restart.
- Client behaviors additionally get a **50 ms wall-clock budget per resume** — exceed it once and
  the client runtime is disabled. `/nodewire clientscripts off` is the global client kill-switch.

---

## 5. The client side

`clientBehavior {}` runs on each client, driven by the render loop. What it can touch:

| Available | Not available |
|---|---|
| `state(..., replicated = true)` cells (read) | non-replicated state (throws) |
| `input<Video>` / `output<Video>` handles | any other `input.value` (throws) |
| `draw { ... }`, `frame()`, `frameDelay()`, `dt()` | writing output pins (ignored — outputs are server-side) |
| `log()` (client console) | `chat()` (deliberate no-op on the client) |

The script **source is replicated** and compiled independently on the client; the top level of the
script runs on *both* sides (it only declares pins/state/behaviors — keep side effects out of it).

---

## 6. Video

Declare video pins and draw:

```kotlin
val cam = input<Video>("cam")            // readable on BOTH sides (auto-mirrored)
val out = output<Video>("out")           // per-node surface handle, minted server-side

clientBehavior {
    while (true) {
        draw(out) {
            image(cam.value)                              // blit the camera feed full-canvas
            border(4, 4, width() - 8, height() - 8, 2, 0xFF_00FF66)
            text("ONLINE", 10, 10, 0xFF_FFFFFF)
        }
        frame()
    }
}
```

`draw {}` closures are captured on the worker and **replayed on the render thread**; they must not
suspend. Up to **64 draws per frame** are buffered, extras are dropped. Draw errors don't crash —
they're logged and shown to the local player.

### Canvas API

Coordinates are pixels, origin top-left, y-down. Colors are **ARGB packed in a `Long`**
(`0xFF_RRGGBB` + alpha in the top byte). The default surface is 256×256 but multiblock screens
resize it — **always use `width()`/`height()`**, never assume 256.

| Call | Notes |
|---|---|
| `width()`, `height()` | current surface size |
| `clear(color)` | fill everything |
| `rect(x, y, w, h, color)` | filled rectangle |
| `border(x, y, w, h, thickness, color)` | hollow rectangle |
| `line(x1, y1, x2, y2, color)` | **axis-aligned only** (a diagonal degrades to its bounding strip) |
| `text(s, x, y, color)` | vanilla font; `textWidth(s)`, `lineHeight()` for layout |
| `image(video)` / `image(video, x, y, w, h)` | blit another feed (weak radio signal auto-noises) |
| `project(video, x, y, z)` | world position → pixel on that camera feed, `null` if not resolvable; off-screen points return out-of-range coords you can clamp into edge markers |
| `dt()`, `time()`, `frames()`, `fps()` | surface redraw metrics |

### Flexbox `ui {}` DSL

```kotlin
draw(out) {
    ui(pad = 8, gap = 4) {                       // root is a full-canvas COLUMN
        row(justify = Justify.SpaceBetween) {
            text("FUEL", 0xFF_AAAAAA)
            text("%d%%".format(pct), 0xFF_00FF66)
        }
        spacer()                                  // grow = 1 filler
        row(height = 24, bg = 0x80_000000, pad = 4) {
            rect(barW, 16, 0xFF_00CC66)           // fixed-size colored box
            image(cam.value, grow = 1f)           // video inside the layout
        }
    }
}
```

Vocabulary: `column(...)`, `row(...)` (both take `grow`, `shrink`, `width`, `height`, `pad`,
`gap`, `justify`, `align`, `bg`, `borderColor`, `borderW`), `text(s, color)`,
`spacer(grow = 1f)`, `rect(w, h, color)`, `image(video, grow)`. `Justify`:
`Start/Center/End/SpaceBetween/SpaceAround/SpaceEvenly`; `Align`: `Start/Center/End/Stretch`.
It's immediate-mode — rebuild the tree every draw.

### Handles

A `Video` is a **UUID handle**, never frames. `output<Video>` mints a unique per-node handle —
prefer it. `video("name")` derives the handle from the name alone, so two nodes using the same
name share one surface (occasionally useful, usually a bug). `Video` equality ignores the
transient `signal` field, so `feed != Video.NONE` works on noisy radio feeds.

---

## 7. Math

Pin vectors (`Vec2`/`Vec3`/`Quat`) are immutable **double-precision** data carriers with no
operators. Do math in JOML (default-imported) and convert at the edges:

```kotlin
val d = target.value.toJoml().sub(gun.value.toJoml(), Vector3d())
aim.value = d.normalize().toVec3()
```

`toJoml()` → `Vector2d`/`Vector3d`/`Quaterniond`; `.toVec2()/.toVec3()/.toQuat()` convert back
(float JOML types convert too). Doubles end to end — world-scale coordinates don't quantize.

---

## 8. CBC ballistics

With Create Big Cannons installed, `Cbc` exposes the **live** munition registry (addon and
datapack projectiles included) plus an exact-trajectory solver:

```kotlin
if (Cbc.available()) {
    val shell = Cbc.shell("he_shell") ?: return@tick        // namespace optional
    val v0 = Cbc.muzzleVelocity(shell, charges = 8.0)       // Σ powder strength; or shell.initialVelocity
    val sol = Cbc.solvePitch(shell, v0, dx = 250.0, dy = -5.0, highArc = false)
    if (sol != null) pitch.value = sol.pitchDeg.toFloat()   // also: flightTicks, impactSpeed
}
```

`Cbc.maxRange(shell, v0)` gives the envelope. The solver replays CBC's per-tick integrator
(gravity + linear/quadratic drag, verified against CBC 5.11) — it's **heavy**: cache the solution
and re-solve a few times per second, not every tick (see the fire-control example below).

---

## 9. Sandbox & limits

Scripts are classloader-sandboxed. Available: `kotlin.*` (safe subset), `kotlin.math.*`,
`java.util` collections, `java.lang.Math`, `java.util.UUID`, **`org.joml.*`**, and the script API.

Blocked — these fail to even load a class: file/network IO, threads, reflection calls
(`kotlin.reflect.full`, `java.lang.reflect`), `System` (no `currentTimeMillis` — use `dt()` or a
tick counter), classloaders, **and the entire `net.minecraft.*` / `net.neoforged.*` API**.
Scripts have *no world access*: no block position, no entities, no game time. Everything comes in
through pins and goes out through pins.

Hard caps: source ≤ **64 KiB**; ≤ **64** `log`/`chat` messages per tick; ≤ **64** `draw {}`
requests per frame; text draws ≤ 256 chars; runaway/budget guards from §4.

---

## 10. Example scripts

### Blinker (state + tick)

```kotlin
val enable = input<Boolean>("enable", default = true)
val lamp   = output<Boolean>("lamp")
var t by state(0)

tick {
    if (!enable.value) { lamp.value = false; return@tick }
    t += 1
    lamp.value = (t / 10) % 2 == 0        // 0.5 s on, 0.5 s off
}
```

### Traffic light (coroutine sequencing)

One `behavior` instead of a hand-rolled state machine — `delay()` does the sequencing. Note the
`phase` state: after a reload the behavior restarts, so it resumes from the persisted phase.

```kotlin
val red    = output<Boolean>("red")
val yellow = output<Boolean>("yellow")
val green  = output<Boolean>("green")
var phase by state(0)

fun set(r: Boolean, y: Boolean, g: Boolean) {
    red.value = r; yellow.value = y; green.value = g
}

behavior {
    while (true) {
        when (phase) {
            0 -> { set(true,  false, false); delay(80) }   // red    4 s
            1 -> { set(true,  true,  false); delay(20) }   // red+yellow 1 s
            2 -> { set(false, false, true);  delay(80) }   // green  4 s
            else -> { set(false, true, false); delay(20) } // yellow 1 s
        }
        phase = (phase + 1) % 4
    }
}
```

### Tap counter (pulse input from a screen / panel button)

```kotlin
val tapped = input<Boolean>("touch_down")   // event pin: true for exactly 1 tick per tap
val taps   = output<Int>("count")
var n by state(0)

tick {
    if (tapped.value) n += 1
    taps.value = n
}
```

### Speedometer HUD (server → client replication + video)

Server computes speed from a position pin; client renders it over the camera feed. This is *the*
canonical replication pattern.

```kotlin
val pos = input<Vec3>("position")           // e.g. a telemetry/aero position pin
val cam = input<Video>("cam")
val out = output<Video>("out")

var speed by state(0f, replicated = true)   // the ONLY thing the client needs
var lastX by state(0f); var lastY by state(0f); var lastZ by state(0f)

tick {
    val p = pos.value
    val d = p.toJoml().sub(Vector3d(lastX.toDouble(), lastY.toDouble(), lastZ.toDouble()))
    speed = (d.length() * 20.0).toFloat()   // blocks per second (20 ticks/s)
    lastX = p.x.toFloat(); lastY = p.y.toFloat(); lastZ = p.z.toFloat()
}

clientBehavior {
    while (true) {
        draw(out) {
            image(cam.value)
            ui(pad = 6) {
                spacer()
                row(justify = Justify.End) {
                    text("%.1f m/s".format(speed), if (speed > 30f) 0xFF_FF5555 else 0xFF_00FF66)
                }
            }
        }
        frame()
    }
}
```

### Touch menu on a screen (touch + draw hit-testing)

A two-button on-screen menu. `touch` is sticky (last tap position in surface px on a Screen
block; a control-panel mini-screen reports 0..1 fractions — scale by `width()`/`height()`),
`touch_down` fires once per tap.

```kotlin
val touch  = input<Vec2>("touch")
val tapped = input<Boolean>("touch_down")
val out    = output<Video>("out")
val optA   = output<Boolean>("mode_a")

var modeA by state(true, replicated = true)

tick {
    if (tapped.value) {
        // buttons occupy the left/right half of the bottom 40 px (256² surface)
        if (touch.value.y > 216) modeA = touch.value.x < 128
    }
    optA.value = modeA
}

clientBehavior {
    while (true) {
        draw(out) {
            clear(0xFF_101418)
            text(if (modeA) "MODE A" else "MODE B", 8, 8, 0xFF_FFFFFF)
            rect(0, height() - 40, width() / 2, 40, if (modeA) 0xFF_00AA55 else 0xFF_223322)
            rect(width() / 2, height() - 40, width() / 2, 40, if (!modeA) 0xFF_0055AA else 0xFF_222233)
            text("A", width() / 4 - 3, height() - 26, 0xFF_FFFFFF)
            text("B", 3 * width() / 4 - 3, height() - 26, 0xFF_FFFFFF)
        }
        frame()
    }
}
```

### CBC ranging (cached heavy solve)

```kotlin
val dist  = input<Float>("distance")
val dy    = input<Float>("height_diff")
val pitch = output<Float>("pitch")
val can   = output<Boolean>("in_range")

var lastDist = -1f                       // plain vars: per-node scratch, no persistence needed

tick {
    if (!Cbc.available()) { can.value = false; return@tick }
    // Re-solve only when the input actually changed — solvePitch is expensive.
    if (dist.value == lastDist) return@tick
    lastDist = dist.value
    val shell = Cbc.shell("he_shell") ?: return@tick
    val v0 = Cbc.muzzleVelocity(shell, charges = 8.0)
    val sol = Cbc.solvePitch(shell, v0, dx = dist.value.toDouble(), dy = dy.value.toDouble())
    can.value = sol != null
    if (sol != null) pitch.value = sol.pitchDeg.toFloat()
}
```

---

## 11. Gotchas checklist

- The **script top level runs on both sides** — declarations only, no side effects there.
- `input.value` (non-Video) and non-replicated state **throw on the client** — route data through
  `state(..., replicated = true)`.
- `chat()` from the client is a silent no-op; `log()` works on both sides.
- Outputs commit at suspend points — don't expect a mid-body write to be visible downstream.
- Downstream sees outputs one tick late (evaluator pipeline).
- Behaviors restart from the top after reload/edit; persist your phase in `state`.
- No `System.currentTimeMillis()` — count ticks or use `dt()`.
- `draw {}` can't suspend; do timing around `frame()` in the enclosing `clientBehavior`.
- Don't assume 256×256 — multiblock screens resize the surface; use `width()`/`height()`.
- `video("name")` shares surfaces by name across nodes; `output<Video>` is per-node.
- A busy loop without `tick()`/`frame()` gets your node disabled after 200 skipped ticks
  (client: or one >50 ms resume). Yield.
- Scripts are saved per client under `<gamedir>/nodewire-scripts/` via the editor's native
  file dialogs.
