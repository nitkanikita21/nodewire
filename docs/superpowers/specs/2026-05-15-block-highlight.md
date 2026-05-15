# Block Highlight — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Give the player a way to visually locate a block in the world from the BindingsManagerScreen or via a client command. A pulsing wireframe cube appears around the block for a few seconds.

Three entry points:
1. **`BlockHighlightRenderer.highlight(pos, durationMs)`** — programmatic API.
2. **`/nodewire highlight <x> <y> <z> [seconds]`** — client-side slash command.
3. **Highlight button** in each `TargetRow` of `BindingsManagerScreen` — invokes (1) locally AND posts a clickable chat message that runs (2), so the user can re-trigger it later from chat history.

## Non-goals

- Server-side highlight broadcast / admin variant.
- Per-channel color of the highlight (fixed light-yellow).
- Sound effect.
- Persistent / always-on highlights.

## Components

### `BlockHighlightRenderer`

New object at `src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt`.

**State:** `private val active = ConcurrentHashMap<BlockPos, Long>()` — pos → expirationTimeMillis. (`ConcurrentHashMap` because `highlight()` may be called from chat-click thread while `render()` runs on the main thread; both safe.)

**Public API:**
```kotlin
fun highlight(pos: BlockPos, durationMs: Long = DEFAULT_DURATION_MS) {
    active[pos] = System.currentTimeMillis() + durationMs
}
```

**Render event handler:** `RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS`. Mirrors `WireWorldRenderer` setup — one `RenderType` (POSITION_COLOR quads, NO_DEPTH_TEST), one `pose.translate(-cameraPos)` pushPose, and a single batch end.

**Drawing per active block:**
- Prune expired entries (`now > expiration` → remove).
- For each surviving entry: draw 12 edges of a wireframe cube around the block, outset 0.02 outside the block bounds. Each edge is a thin quad (thickness 0.04) — reuse the `emitEdge`/`emit` helpers' approach from `WireWorldRenderer` (copy minimally; don't refactor cross-file).
- Color: light yellow `0xFFFFE066` base. Alpha modulated by `pulse = 0.6f + 0.4f * sin(now * (2 * PI / 500))` (period 500ms → ~2 Hz pulses). Multiply the base alpha (FF=255) by `pulse` (0.2..1.0).

**Constants:**
```kotlin
private const val DEFAULT_DURATION_MS = 3000L
private const val EDGE_THICKNESS = 0.04
private const val OUTSET = 0.02
private const val PULSE_PERIOD_MS = 500.0
```

### `HighlightCommand`

New file: `src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt`.

Registers on `RegisterClientCommandsEvent`:
```
/nodewire highlight <pos> [seconds]
```

- `<pos>` is `BlockPosArgument.blockPos()` — accepts `~` relative and absolute coords.
- `[seconds]` is optional `IntegerArgumentType.integer(1, 60)`.
- Executor: resolves `BlockPos` via `BlockPosArgument.getBlockPos(ctx, "pos")`, calls `BlockHighlightRenderer.highlight(pos, seconds * 1000L)`, returns 1.
- No feedback message on success (avoid chat spam when re-clicking the bindings-manager button).

### `BindingsManagerScreen` integration

Modify the private `TargetRow` Composable:
- Add a `targetPos: BlockPos` parameter.
- Insert a new `Button` immediately before the existing `×` button. Label: `◎` (unicode bullseye). Style: `ButtonDefaults.primary()` (or whatever neutral preset exists — pick the non-danger one). Padding identical to the `×` button so visual rhythm is preserved.
- On click:
  1. Call `BlockHighlightRenderer.highlight(targetPos)`.
  2. Compose a chat message: `Component.literal("Highlight (X, Y, Z) again")` styled as underlined yellow, with a `ClickEvent(RUN_COMMAND, "/nodewire highlight X Y Z")` and a `HoverEvent.SHOW_TEXT` of `"Click to re-highlight"`. Send via `Minecraft.getInstance().player?.displayClientMessage(component, false)`.

Both call sites for `TargetRow` (the channel-binding row and side-binding row) pass their respective `targetPos`.

### Event registration

In `NodewireClient.kt`, add to the existing client-mod-bus subscriptions:
- `FORGE_BUS.addListener<RenderLevelStageEvent>(BlockHighlightRenderer::onRender)` (or whatever method name the renderer exposes — mirror `WireWorldRenderer::render`).
- `MOD_BUS.addListener<RegisterClientCommandsEvent>(HighlightCommand::register)`.

## Files touched

- Create: `src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/NodewireClient.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

## Tests

Renderer + command — not unit-tested (pure draw / Forge registration). Smoke checklist:

1. `/nodewire highlight 10 64 10 5` → block at (10, 64, 10) pulses for 5 seconds.
2. `/nodewire highlight ~ ~ ~` → block at player position highlights.
3. In a logic block's BindingsManagerScreen, click `◎` next to a binding → its target block pulses for 3s + chat receives `Highlight (X, Y, Z) again` as clickable yellow text.
4. Click the chat message → block pulses again, no duplicate chat entry.
5. Wait 3s → pulse fades. After ~500ms cycle, alpha visibly oscillates while active.
6. Highlight + walk behind a wall → still visible (through-wall via NO_DEPTH_TEST).

## Out of scope

- Highlight propagation to other players.
- Highlight from `/give`-style admin commands (server tab-completion only — separate spec).
- Animation polish (easing, scale-pulse instead of alpha-pulse).
- Highlight on hover-over-wire in world.
