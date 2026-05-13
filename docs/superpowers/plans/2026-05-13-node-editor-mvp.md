# Node Editor MVP — Implementation Plan

**Spec:** [`docs/superpowers/specs/2026-05-13-node-editor-mvp-design.md`](../specs/2026-05-13-node-editor-mvp-design.md)

**Goal:** Editable typed node graph stored in `LogicBlockEntity`'s NBT. Right-click the block → editor opens; drag from palette → wire pins → close → graph persists. **No execution.**

**Foundations already done:** Compose UI framework, theme, overlay/scroll/dialog, `NodeCanvas` with pan/zoom/grid.

## Phase order

Each phase ends in a commit. Phases 1–4 are pure backend (no UI), 5–9 are UI on top.

### Phase 1 — Graph data model (pure Kotlin, no MC)
- `graph/PinType.kt` — enum + NBT codec
- `graph/PinValue.kt` — sealed class hierarchy + NBT codec
- `graph/Pin.kt`, `PinRef.kt`, `Edge.kt`
- `graph/Node.kt` (abstract) + `NodeId`
- `graph/NodeGraph.kt` with `toNbt`/`fromNbt`
- JUnit round-trip tests

### Phase 2 — NodeType registry + 13 stock types
- `graph/NodeType.kt` — registry entry
- `graph/NodeTypes.kt` — DeferredRegister wiring
- `graph/types/` — 13 type registrations from spec table

### Phase 3 — LogicBlock + BlockEntity
- `block/LogicBlock.kt` — `use()` will open the screen (stub for now)
- `block/LogicBlockEntity.kt` — `graph` field + NBT save/load + sync
- Register in `Registry.kt`; creative tab entry; item model + blockstate JSONs

### Phase 4 — Network packet
- `net/NodewireNetwork.kt` — SimpleChannel
- `net/SaveGraphPacket.kt` — server-bound, validates and applies

### Phase 5 — Node card composable
- `client/screen/NodeCardComposable.kt` — Surface with title bar + pin rows
- Pin handle as small circle; left-drag on title moves the card (updates Node.pos)

### Phase 6 — Wire rendering
- `client/screen/WireRenderer.kt` — bezier curves on the canvas, between pin handle world positions
- Pin position tracking via `onPositioned` modifier on the handle composable
- Wire color from source pin type

### Phase 7 — Wire interaction
- Drag from output pin → temp wire follows cursor
- Drop on compatible input → create edge
- Type validation preview: green ring on compatible target, red on incompatible
- Cancel on canvas drop

### Phase 8 — Palette
- `client/screen/Palette.kt` — left-side overlay with categorized list
- Click entry → spawn node at canvas center

### Phase 9 — Full screen integration
- `client/screen/NodeEditorScreen.kt` — replaces DemoScreen as right-click target
- Loads graph from BE on open (deep copy for local edits)
- Sends `SaveGraphPacket` on close
- Confirm dialog on close-while-dirty (using existing `Dialog`)
