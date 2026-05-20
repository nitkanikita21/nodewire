# Usage

Short reference for everyday use. For build / contributor info see the main [`README`](../README.md).

## Opening the editor

Place a **Nodewire block** and right-click it to open the in-world node editor.

For testing without placing a block, press **N** in any world — this opens the dev demo screen bound from `NodewireClient`.

## Editing workflow

1. **Right-click on the canvas** → context menu with `Add node ▸ <category>`, `Add Comment`, `Insert group ▸ …`.
2. **Pick a node** from the category submenu. It spawns at the click position.
3. **Drag from an output pin** to an input pin to draw a wire. Pin colours indicate the value type (bool / int / float / vec2 / vec3).
4. **Configure** a node by clicking it — the config sheet shows its editable fields (constant value, math op, channel, etc.).
5. **Toolbar** at the top has `File ▸ Save / Save as / Open / New`, `Edit ▸ Undo / Redo / …`, `View ▸ Frame all / Frame selection`.

Graphs are saved per client under `<gamedir>/nodewire-graphs/<name>.snbt`. Group templates live under `<gamedir>/nodewire-groups/`.

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
| `F`                  | Frame selection (or all, if none selected) |
| `Shift+F`            | Frame all                               |
| `Ctrl+G`             | Group selection                         |
| `Ctrl+Shift+G`       | Ungroup containing group                |
| `Esc`                | Cancel rename → close menu → clear selection |
| Double-LMB on header | Rename node / group inline              |
| Drag on empty canvas | Marquee — selects nodes + groups + comments (Shift = additive) |

Open `N` (outside the editor) opens the demo screen.

## Node categories

- **IO** — `Side Input` / `Side Output` (read/write redstone on a block face), `Channel Input` / `Channel Output` (block-local named channels), `Redstone Link Input` / `Output` (Create wireless redstone — see [Integrations](#integrations)).
- **Logic** — `Logic Gate` (AND / OR / NOT / XOR / NAND / NOR / XNOR).
- **Math** — `Math` (+, −, ×, ÷, min, max, abs, …), `Compare` (==, ≠, <, ≤, >, ≥).
- **Conversion** — `Convert` between bool / redstone level / int / float.
- **Flow** — `Select Bool`, `Rising Edge`, `Toggle`, `Counter`, `Delay`.
- **Constants** — `Constant` (any value, incl. `Vec2` / `Vec3`), `Timer`, `Pulse`, `Random Bool`, `Random Int`.
- **Vector** — `Vec Make` (compose `Vec2` / `Vec3` from scalars), `Vec Split` (decompose), `Vec Op` (per-component arithmetic, dot, length, normalize, …).
- **Groups** are not nodes — they're visual containers that wrap a subset of the graph. They can be collapsed into a single tile, saved as a reusable template, and live-edited.
- **Comments** are floating markdown-like text boxes for annotating the graph; they are not part of evaluation.

Right-clicking an existing node / group / comment / wire opens its own context menu (rename, delete, save as template, collapse, set label, …).

## Integrations

All integrations are mod-gated — Nodewire works without any of them, and unlocks extra nodes / features when the corresponding mod is present.

### Sable

> `master` (Forge 1.20.1) integrates with **Valkyrien Skies 2** instead. The Sable backend below is the NeoForge 1.21.1 replacement on `port/neoforge-1.21.1`.

Nodewire blocks placed on a Sable sub-level continue to evaluate across the sub-level/world boundary. `Side Input` / `Side Output` resolve through the parent `Level`'s `getBlockEntity` (sub-levels live as plot regions in the parent level), so positions stay correct as the sub-level rotates / translates. Endpoint references that point to a block in a sub-level use a `(subLevelId, BlockPos)` payload and survive motion. World-space center / direction queries apply `SubLevelAccess.logicalPose()` server-side and `ClientSubLevelAccess.renderPose()` client-side (smooth partial-tick).

Sable Companion provides safe no-op defaults so Nodewire compiles and runs without Sable installed.

### Create Aeronautics

When Aeronautics is loaded, an **`Aeronautics Input`** node becomes available. Aircraft built by Aeronautics ARE Sable sub-levels, so wires across an aircraft "just work" through the Sable backend. On top of that, the Aeronautics Input node exposes per-block state — pick a block kind and a channel:

- **Smart / Andesite / Wooden Propeller** — RPM, target RPM, signal, …
- **Hot-Air Burner** — temperature, fuel, signal (writable).
- **Steam Vent** — pressure, signal (writable).
- **Mounted Potato Cannon** — yaw, pitch, charge, …
- **Propeller Bearing** — angle, RPM, target RPM, …

The output pin reshapes to the channel's type. Hold the Channel Link Tool and sneak+RMB the target Aero block, then pick a channel in the popup. ~33 channels across the 7 supported block kinds.

### Create

When Create is loaded, two extra IO nodes appear:

- **`Redstone Link Input`** — reads a Create wireless-redstone frequency.
- **`Redstone Link Output`** — writes to a frequency.

Frequencies are configured with two item slots (Create's standard two-slot frequency model). The slots accept drag-and-drop from inventory, **and** from JEI / EMI ghost ingredients (see below).

### Tweaked Controller

When `tweakedcontroller` is loaded, the **`Controller Input`** node becomes available. Bind it to a controller (held hub item handles pairing), then pick a channel:

- **Composite sticks** — `Left Stick`, `Right Stick`, `D-Pad` — output either a `Vec2` (raw) or split `X` / `Y` (raw or as redstone level 0-15).
- **Buttons** — `A` / `B` / `X` / `Y` / shoulders / thumbsticks / menu buttons — output `Bool` or `Redstone`.
- **Triggers** — `Left Trigger` / `Right Trigger` — output `Float` (raw 0-1), `Redstone` (0-15), or `Bool` (past deadzone).
- **Half-axes** — individual stick directions as scalar axes, where applicable.

Output pins reshape based on the selected channel and output-mode. When the controller is offline or unbound the node emits zero values.

### JEI / EMI

The JEI and EMI plugins both register a **ghost-ingredient handler** for the redstone-link frequency slots in the editor — drag an item from the JEI/EMI ingredient list directly onto a slot to set its frequency. No inventory roundtrip needed.
