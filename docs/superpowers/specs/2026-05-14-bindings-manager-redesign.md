# Bindings Manager redesign

## Context

Shift + right-click on a `LogicBlock` with the Channel Link Tool opens
`BindingsManagerScreen` — used to (a) arm the tool with a source channel
and (b) inspect/remove outgoing bindings from this block.

The current screen has two flaws:

- Layout: each binding renders as two stacked lines (source name on top,
  arrow + target underneath) inside a flat list. Reads as visually noisy
  and "cringe" per direct feedback.
- The `×` button is a hand-rolled `Box` with manual pointer handling —
  hover/pressed states are inconsistent with the rest of the UI.

This spec replaces the layout with a grouped tree, unifies source-pick
with binding management into a single list, and switches the remove
control to the existing `Button(ButtonDefaults.danger())` component.

## Goals

- One screen, one list. Each `channel_output` on this block becomes one
  group. Group header doubles as the source-pick affordance.
- Per binding: one short line. `→ (x, y, z) target  chip  ✕`. No more
  two-line per binding.
- Use the real `Button` component for delete — correct hover/pressed
  feedback, accessible affordance.
- Visual distinction between channels that are wired vs idle so the user
  can pick an unused channel at a glance.

## Non-goals

- Disconnecting from the *target* side (clicking on a non-logic block's
  bound face). Out of scope for this round.
- Editing the source channel's name/type from this screen. That lives in
  the node editor.
- Bulk operations (delete-all, multi-select). Single binding per click.

## Layout

```
┌────────────────────────────────────────────────────┐
│ Link Manager              5 channels · 3 bindings  │
│ Block (12, 64, -8) · click a channel to arm…       │
│                                                    │
│ ● speed         INT          2 bindings            │   ← clickable header
│   →  (12, 64, -8) thrust          [ch]   [✕]       │
│   →  (13, 64, -8) main            [ch]   [✕]       │
│                                                    │
│ ● latch         REDSTONE     1 binding             │
│   →  (40, -3, 5) ↑                [side] [✕]       │
│                                                    │
│ ● door_lock     BOOL         no bindings           │
│                                                    │
│ ◌ thruster_power FLOAT       no bindings           │   ← dashed (idle)
│ ◌ handbrake     BOOL         no bindings           │
└────────────────────────────────────────────────────┘
```

- **Group headers** are `Row { dot · name · type-chip · spacer · count }`.
  Background tokens:
  - Wired (≥1 binding): `surfaceHover` solid.
  - Idle (no bindings): transparent + 1 px solid `borderThin` token with
    50% alpha applied to the colour. (The Yoga-backed Border modifier only
    supports solid strokes; we approximate the "muted/idle" feel with
    reduced opacity instead of a dashed pattern.)
  - Hovered: shift to `surfacePressed` for both states.
- **Click on header** = pick this channel as source: writes to stack NBT,
  fires the `onPickSource` callback, closes the screen. The whole row is
  the click target — no separate "pick" button.
- **Target rows** are indented 16 px under their group header. Layout:
  `Row { arrow · description · spacer · kind-chip · ✕ }`.
  - Channel binding: `description = "(x, y, z) channelName"`.
  - Side binding: `description = "(x, y, z) icon"` where `icon` is a
    single character: `↑↓` for `UP/DOWN`, `N S W E` for cardinal.
- **kind-chip**: small `caption`-style pill: `ch` for channel,
  `side` for side binding. Same `surfacePressed` background.
- **Delete (`✕`)** is `Button(ButtonDefaults.danger())` with `×` glyph and
  tight padding (`PaddingValues(horizontal = space4, vertical = space2)`).

## Empty states

| Condition                                | Render                                                            |
|------------------------------------------|-------------------------------------------------------------------|
| Block has zero `channel_output` nodes    | One subtle line: "Add a Channel Output node to this block first." |
| Has channel_outputs but zero bindings    | Idle groups still show — no special empty message.                |

## Interactions

| Trigger                          | Behaviour                                                            |
|----------------------------------|----------------------------------------------------------------------|
| Click group header               | Arm tool with this source; close screen.                             |
| Click `✕` on target row          | Send `RemoveBindingPacket`; bump local `version` to recompose; screen stays open. |
| Click outside the panel / ESC    | Close without action.                                                |
| Hover anywhere                   | Surface hover for headers + rows, danger hover for `✕`.              |

## Side icon mapping

| Direction | Glyph |
|-----------|-------|
| `UP`      | `↑`   |
| `DOWN`    | `↓`   |
| `NORTH`   | `N`   |
| `SOUTH`   | `S`   |
| `WEST`    | `W`   |
| `EAST`    | `E`   |

Cardinal directions use letters rather than horizontal arrows because
`←→` on a 2D screen is ambiguous without a world-axis legend, and the
single-letter form fits the compact single-line target row.

## Implementation notes

- Existing classes touched:
  - `client/screen/BindingsManagerScreen.kt` — full rewrite of the
    composable body. Public constructor signature (`sourceBe`,
    `onPickSource`) unchanged.
- No data-model or network changes. `RemoveBindingPacket`,
  `LogicBlockEntity.removeBinding`/`removeSideBinding` stay as is.
- Use `Button` from `ui/components/Button.kt` for the delete control.
  Drop the hand-rolled `Box`-with-`pointerInput` row.
- Panel width: bump to **400 px** (currently 380) to give the new
  header row breathing room for `count` on the right.
- Keep the outside-click-to-dismiss `Box(fillMaxSize).pointerInput`
  wrapper from the current implementation.
- Coords formatter helper: `BlockPos.toShortString` already returns
  `"x, y, z"` — use it directly. Wrap in parentheses inline:
  `"(${pos.toShortString()})"`.

## Out of scope (future)

- Inspecting incoming bindings on a non-logic target (would need a
  reverse index across all `LogicBlockEntities` on the server). Adds
  meaningful complexity; defer until users ask.
- Click a wire in 3D to disconnect. Requires 3D quad picking; defer.
- Rename / retype source channels from this screen. The node editor is
  authoritative for graph edits.
