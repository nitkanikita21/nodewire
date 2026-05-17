# Editor QoL — Keyboard / Clipboard / Undo / Frame Design Spec

First QoL batch for the node editor: hotkey-driven selection ops (Delete, Ctrl+A/D), copy/cut/paste of subgraphs through the system clipboard, graph-level undo/redo with drag-merge, and frame-selected / frame-all (F / Shift+F) camera helpers.

## Goal

Editor stops feeling like a tech demo. Common ops — delete, duplicate, undo a wrong move, paste a subgraph between blocks — are one keystroke away. Drag a multi-node selection by 200px → it's one undo step, not 100.

## Architecture

Five focused additions, no major refactors:

1. **`GraphUndoController`** — snapshot-based undo/redo with 500ms merge window for continuous ops (drag, slider). Capped at 50 entries. Lives inside `EditorState`.
2. **`mutateGraph(mergeable, block)` helper** — single entry point all graph mutators route through. Pushes snapshot, runs block, refreshes flows. Existing `EditorState` methods (addNode, removeNode, moveSelectedNodes, change*, etc.) become one-line wrappers.
3. **`GraphClipboard`** — serialize selected subgraph through existing `NodeGraph.CODEC` wrapped in a marker compound; deserialize on paste with UUID regen + position translate.
4. **`EditorKeyBindings`** — data-driven shortcut table mirroring `TextFieldKeyBindings` from the TextInput rewrite. Dispatched from `NodeEditorScreen.keyPressed` only when no TextInput holds focus.
5. **Frame/fit** — `CanvasState.frameRect(...)` + `EditorState.nodeBounds` (per-card AABB published from `NodeCard.onPositioned`).

No new Compose primitives. No new mod-API dependencies. Pure additions to the editor layer.

## Shortcut table

| Key | Action | Notes |
|---|---|---|
| `Delete` / `Backspace` | Delete selected nodes | Single undo step; removes connected edges. |
| `Ctrl+A` | Select all nodes | |
| `Ctrl+D` | Duplicate selected | Offset +20/+20 world; selects copies. |
| `Ctrl+C` | Copy selected → clipboard | SNBT subgraph (see Clipboard section). |
| `Ctrl+X` | Cut selected → clipboard | Copy + delete in one undo step. |
| `Ctrl+V` | Paste at cursor | Centroid of pasted nodes = world cursor pos. |
| `Ctrl+Z` | Undo | Pops undo, pushes redo. |
| `Ctrl+Y` / `Ctrl+Shift+Z` | Redo | |
| `F` | Frame selected (or all if none selected) | |
| `Shift+F` | Frame all | |
| `Esc` | Close menu / clear selection | First open menu, then selection, else pass through. |

Modifier match uses the same `MOD_MASK = CTRL|SHIFT|ALT|SUPER` filter as `TextFieldKeyBindings` so Caps/Num lock don't break matches.

### Dispatch order in `NodeEditorScreen.keyPressed`

1. `super.keyPressed(...)` — forwards into `NwUiOwner.dispatchKey`. If a focused TextInput consumes (returns true), we're done.
2. If `owner.keyFocus` is non-null (a TextInput is focused even if it didn't consume the specific key), return false. Editor shortcuts shouldn't steal Ctrl+Z from an in-progress text edit.
3. Else: `EditorKeyBindings.match(event)?.action?.invoke(editor)`. Return its result.

## Graph clipboard

Wrap `NodeGraph.CODEC` output in a marker compound so paste can recognise our format and silently ignore foreign clipboard contents.

```
{
  nodewire_subgraph: 1b,
  version: 1,
  graph: { nodes: [...], edges: [...] }    // NodeGraph.CODEC payload
}
```

### Copy

```kotlin
fun copySelectedToClipboard() {
    if (selectedNodes.isEmpty()) return
    val sub = NodeGraph().apply {
        for (n in graph.nodes.values) if (n.id in selectedNodes) nodes[n.id] = n
        for (e in graph.edges) {
            if (e.from.node in selectedNodes && e.to.node in selectedNodes) edges.add(e)
        }
    }
    val payload = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, sub).result().orElseThrow()
    val wrapper = CompoundTag().apply {
        putBoolean("nodewire_subgraph", true)
        putInt("version", 1)
        put("graph", payload)
    }
    Minecraft.getInstance().keyboardHandler.clipboard = wrapper.toString()
}
```

### Cut

`copySelectedToClipboard()` then `deleteSelected()` inside one `mutateGraph` step.

### Paste

```kotlin
fun pasteFromClipboard(cursorWorldX: Float, cursorWorldY: Float) {
    val raw = Minecraft.getInstance().keyboardHandler.clipboard ?: return
    val wrapper = runCatching { TagParser.parseTag(raw) as? CompoundTag }.getOrNull() ?: return
    if (!wrapper.getBoolean("nodewire_subgraph")) return
    val graphTag = wrapper.get("graph") ?: return
    val sub = NodeGraph.CODEC.parse(NbtOps.INSTANCE, graphTag).result().orElse(null) ?: return

    // Regen UUIDs; drop nodes whose type is no longer registered.
    val idMap = HashMap<NodeId, NodeId>()
    val newNodes = sub.nodes.values.mapNotNull { old ->
        if (NodeTypeRegistry.get(old.typeKey) == null) return@mapNotNull null
        val newId = UUID.randomUUID()
        idMap[old.id] = newId
        old.copy(id = newId)
    }
    if (newNodes.isEmpty()) return

    // Translate so centroid lands at cursor.
    val cx = newNodes.map { it.x }.average().toFloat()
    val cy = newNodes.map { it.y }.average().toFloat()
    val dx = cursorWorldX - cx; val dy = cursorWorldY - cy
    val translated = newNodes.map { it.copy(x = it.x + dx, y = it.y + dy) }

    // Rewrite edge endpoints; drop edges that reference dropped nodes.
    val newEdges = sub.edges.mapNotNull { e ->
        val newFromId = idMap[e.from.node] ?: return@mapNotNull null
        val newToId = idMap[e.to.node] ?: return@mapNotNull null
        e.copy(from = e.from.copy(node = newFromId), to = e.to.copy(node = newToId))
    }

    mutateGraph {
        translated.forEach { graph.nodes[it.id] = it }
        graph.edges.addAll(newEdges)
    }
    selectMany(translated.map { it.id })
}
```

Random clipboard text → `TagParser.parseTag` throws → `runCatching` returns null → silent no-op. Foreign-but-valid SNBT without the marker → also no-op. Ctrl+V never disturbs other-app clipboard content.

## Undo controller

Snapshot-based. `NodeGraph` re-serialized through its existing `CODEC` for deep copy — already wired for safe round-trip, no need for a hand-rolled cloner.

```kotlin
class GraphUndoController(private val nowMs: () -> Long = { Util.getMillis() }) {
    private val undoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private val redoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private var lastPushAt = 0L
    private var lastMergeable = false
    private val mergeWindowMs = 500L
    private val cap = 50

    fun snapshot(state: NodeGraph, mergeable: Boolean) { /* ... */ }
    fun undo(current: NodeGraph): NodeGraph?
    fun redo(current: NodeGraph): NodeGraph?
    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
}

fun NodeGraph.deepCopy(): NodeGraph {
    val tag = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElseThrow()
    return NodeGraph.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
}
```

Snapshot is taken **before** the mutation. Undo restores that snapshot and pushes the current (post-mutation) state onto redo. Redo reverses.

### `mutateGraph` entry point

```kotlin
// EditorState
private val undo = GraphUndoController()

fun mutateGraph(mergeable: Boolean = false, block: () -> Unit) {
    undo.snapshot(graph.deepCopy(), mergeable)
    block()
    rebuildNodeFlows()
}

fun undoGraph() {
    val prev = undo.undo(graph.deepCopy()) ?: return
    graph = prev
    rebuildNodeFlows()
    clearSelection()
}

fun redoGraph() {
    val next = undo.redo(graph.deepCopy()) ?: return
    graph = next
    rebuildNodeFlows()
    clearSelection()
}
```

### Mutators routed through

All existing graph-mutating EditorState methods get a one-line wrapper:

- `addNode` (mergeable=false)
- `removeNode` / `deleteSelected` / `disconnectAllEdges` / `disconnectPin` (false)
- `updateNode` (mergeable=true — config edits often arrive frame-by-frame from sliders/TextInputs; merge so a single drag = single undo)
- `moveSelectedNodes` (mergeable=true)
- `change*` family — `changeChannelType`, `changeLogicGateOp`, `changeMathConfig`, `changeCompareType`, `changeConstantType`, `changeConvertTypes`, `changeConvertMode` (false; single discrete UI action)
- `commitWireTo` (false)
- `duplicateNode` (false)
- `duplicateSelected` (false)
- New: `copySelectedToClipboard` doesn't mutate. `pasteFromClipboard` (false). `cutSelectedToClipboard` (false).

Selection-only state (`selectedNodes`, `selectionDragStart/Current`, wire-drag in-progress) is NOT snapshotted. Undo never restores selection — clear-selection-on-undo is the convention.

`setBlockName` — not a graph mutator (separate NBT slot). Not undoable.

### What stays NOT undoable (by design)

- Selection changes (single-click selectOnly, Shift+click toggle, rubber-band).
- Pan/zoom of canvas.
- Wire-drag in progress (mid-drag); only the `commitWireTo` end-point creates an undo step.
- BlockName changes.

## Frame / fit

`CanvasState` gains:

```kotlin
private var _visibleWidthPx by mutableStateOf(0)
private var _visibleHeightPx by mutableStateOf(0)
val visibleWidthPx: Int get() = _visibleWidthPx
val visibleHeightPx: Int get() = _visibleHeightPx
fun setSize(w: Int, h: Int) { _visibleWidthPx = w; _visibleHeightPx = h }

fun setZoom(z: Float) { _zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM) }
fun setPan(x: Float, y: Float) { _panX = x; _panY = y }

fun frameRect(minX: Float, minY: Float, maxX: Float, maxY: Float, marginPx: Int = 32) {
    val viewW = _visibleWidthPx; val viewH = _visibleHeightPx
    if (viewW <= 0 || viewH <= 0) return
    val w = maxX - minX; val h = maxY - minY
    if (w <= 0 || h <= 0) return
    val zoomFit = minOf(
        (viewW - marginPx * 2f) / w,
        (viewH - marginPx * 2f) / h,
    ).coerceIn(MIN_ZOOM, MAX_ZOOM)
    setZoom(zoomFit)
    val midX = (minX + maxX) * 0.5f
    val midY = (minY + maxY) * 0.5f
    setPan(viewW * 0.5f / zoomFit - midX, viewH * 0.5f / zoomFit - midY)
}
```

`NodeCanvas` updates `setSize(w, h)` via `onSizeChanged` (or alongside the existing `onPositioned` that already publishes origin).

`EditorState` adds `nodeBounds: SnapshotStateMap<NodeId, NodeBounds>` where `NodeBounds(width: Int, height: Int)`. `NodeCard.onPositioned` writes its `layoutWidth`/`layoutHeight` into this map.

```kotlin
fun frameSelectedOrAll() {
    val ids = if (selectedNodes.isNotEmpty()) selectedNodes else graph.nodes.keys
    frameNodes(ids)
}
fun frameAll() = frameNodes(graph.nodes.keys)

private fun frameNodes(ids: Set<NodeId>) {
    if (ids.isEmpty()) return
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (id in ids) {
        val n = graph.nodes[id] ?: continue
        val b = nodeBounds[id] ?: NodeBounds(120, 60)   // fallback if pre-layout
        minX = minOf(minX, n.x);              minY = minOf(minY, n.y)
        maxX = maxOf(maxX, n.x + b.width);    maxY = maxOf(maxY, n.y + b.height)
    }
    canvasState.frameRect(minX, minY, maxX, maxY)
}
```

## Esc semantics

Editor-level Esc lives in the same `EditorKeyBindings` table but with a priority shape:

1. If `editor.contextMenu` is open → close it, return true.
2. Else if `selectedNodes.isNotEmpty()` → `clearSelection()`, return true.
3. Else return false (lets `NwComposeScreen` close-screen pass through).

Popups (BindingsManagerScreen, channel picker etc.) already register their own dismiss-on-Esc — they're separate screens so Esc to them never reaches this branch.

## Edge cases

- **Paste into different block** (clipboard copied from one LogicBlock, pasted into another): unique-graph isolation is automatic — UUIDs regenerated, edges within subgraph remapped, edges to outer nodes were already excluded at copy time. Result: subgraph spawns disconnected, ready for the user to wire up.
- **Paste with stale node type** (e.g. clipboard from older mod version with a node type later renamed): unknown `typeKey` → node skipped silently. Logged at INFO. Edges referencing that node dropped.
- **Undo across editor close**: undo state is per-EditorState instance. Closing the editor = save graph + drop state. Reopening = fresh empty undo stack with the current graph as base.
- **Wire-drag in progress + shortcut**: `keyPressed` checks if `owner.pointerFocus != null` (active drag); if so, only Esc (cancel) is allowed; destructive shortcuts ignored. Cancels wire-drag explicitly.
- **`onValueChange` from TextInput inside a node config** (e.g. ChannelEndpoint name) routes through `updateNode → mutateGraph(mergeable=true)`. So typing 5 chars produces 1 graph-undo step that reverts to pre-typing config. (TextInput's own per-char undo is separate, within the focused field.)
- **Multi-line clipboard with our marker**: SNBT round-trip via `CompoundTag.toString()` produces a single-line representation; `TagParser.parseTag` handles it. No newlines, no issues.

## Manual test plan

1. **Delete**: 3 nodes, select 2, press Delete → 1 left. Wired connections to deleted nodes also gone.
2. **Ctrl+Z** restores all 3 plus the wires.
3. **Ctrl+Y** redoes the delete.
4. **Ctrl+A** selects every node.
5. **Ctrl+D** duplicates selection +20/+20.
6. **Ctrl+C + Ctrl+V**: 2 selected nodes → copy → click in empty area → paste → 2 new nodes appear centred at cursor with new UUIDs.
7. **Ctrl+X**: same as copy, source removed.
8. **Drag-move-then-Ctrl+Z**: drag a 5-node selection by 200px → single Ctrl+Z restores all to original positions.
9. **Type-then-Ctrl+Z while TextInput focused**: TextInput's own undo fires, graph undo unaffected. Click outside → press Ctrl+Z → graph reverts to pre-typing config.
10. **F with selection** centres on AABB of selected. **F without selection** centres on all. **Shift+F** always centres on all.
11. **Esc** sequence: open context menu via right-click, press Esc → menu closes, selection preserved. Press Esc again → selection clears.
12. **Cross-block paste**: copy in LogicBlock A's editor, close, open LogicBlock B, paste → subgraph appears in B.
13. **Foreign clipboard**: copy text "hello" from another app, press Ctrl+V in editor → nothing happens, no crash.
14. **Undo across 60 ops** → stack capped at 50; oldest dropped; redo only goes back to ones still in stack.

## File layout

**New:**
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt` — data table + match.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphClipboard.kt` — serialize / parse subgraph wrapper.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphUndoController.kt` — undo/redo stacks + merge.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/CanvasFraming.kt` — `NodeBounds` data class + `frameRect` extension on CanvasState.
- `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphUndoControllerTest.kt` — push/undo/redo/merge tests (uses real `NodeGraph.deepCopy`; verify it works in pure-JVM tests).
- `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphClipboardTest.kt` — round-trip, selection filter, unknown-format silent no-op.

**Modified:**
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — `mutateGraph` helper; wrap all mutators; add `deleteSelected`, `selectAll`, `duplicateSelected`, `copySelectedToClipboard`, `cutSelectedToClipboard`, `pasteFromClipboard`, `frameSelectedOrAll`, `frameAll`, `undoGraph`, `redoGraph`, `nodeBounds`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` — override `keyPressed` for editor-level dispatch.
- `src/main/kotlin/dev/nitka/nodewire/ui/canvas/CanvasState.kt` — `visibleWidthPx/Height` + `setSize` + `setZoom` + `setPan` + `frameRect`.
- `src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt` — wire `onSizeChanged` to `canvasState.setSize(...)`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` — push `(layoutWidth, layoutHeight)` to `editor.nodeBounds[id]` from `onPositioned`.

## Out of scope (separate sub-projects)

- Quick-spawn palette (Tab → fuzzy search → spawn).
- Wire utilities (reroute knots, Alt-click disconnect, drag-pin-into-empty → spawn).
- Visual organisation (frames/groups, sticky comments, color tags, mute/disable, compact mode).
- Navigation polish (minimap, snap-to-grid, view undo).
- Tooltips / status badges / F2-rename / Ctrl-drag duplicate.

## Decisions log

- **Snapshot undo over command pattern.** Graphs small (~50 nodes typical), snapshots ~10KB, deep-copy via existing CODEC. Implementation is a fraction of command-per-op size.
- **`NodeGraph.CODEC` for clipboard.** Don't invent a parallel subgraph format; subgraph is a NodeGraph with a filter applied.
- **Marker compound wraps payload.** `nodewire_subgraph: 1b` prevents foreign clipboard text from misbehaving on paste.
- **Selection NOT undoable.** Restore on undo would feel weird for cross-step pile-ups; cleared selection is the standard editor convention.
- **Editor shortcut dispatch via `NodeEditorScreen.keyPressed` fall-through.** No new owner-level fallback API needed — the screen sees both forwarded-to-owner result and key event, can pick what to do.
- **`mergeable=true` on drag + slider config.** Discrete clicks (add/remove/wire) each get their own undo step.
