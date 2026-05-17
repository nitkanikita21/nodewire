# Editor QoL Batch 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First QoL batch for the node editor — hotkey-driven Delete/Ctrl+A/D/C/X/V/Z/Y/F/Shift+F, copy/paste subgraphs through the system clipboard, graph-level undo/redo with drag-merge, frame-selected / frame-all.

**Architecture:** `GraphUndoController` (snapshot-based, 500ms merge, cap 50) inside `EditorState`. `mutateGraph(mergeable, block)` is the single entry point all graph mutators route through. `GraphClipboard` wraps `NodeGraph.CODEC` output in a marker compound. `EditorKeyBindings` data table mirrors `TextFieldKeyBindings`; dispatched from `NodeEditorScreen.keyPressed` as a fall-through after MC-owner forwarding. Frame helpers use a new `nodeBounds: SnapshotStateMap<NodeId, NodeBounds>` populated by `NodeCard.onPositioned`, plus a new `CanvasState.frameRect` + abs setters.

**Tech Stack:** Kotlin 2.0.20, Forge 1.20.1, Mojang Codecs/NbtOps, Compose runtime, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-17-editor-qol-keyboard-clipboard-undo-design.md`

---

## File Structure

**New:**
- `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphUndoController.kt` — undo/redo stacks, merge window, snapshot via `NodeGraph.deepCopy`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphClipboard.kt` — `encode(NodeGraph)` / `decode(String)` with marker wrapper.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeBounds.kt` — data class `NodeBounds(width: Int, height: Int)`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt` — `EditorKeyBinding` data class + `DEFAULT` list + `match` function.
- `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphUndoControllerTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphClipboardTest.kt`

**Modified:**
- `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt` — add `deepCopy()` extension/method (codec round-trip).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — wire `GraphUndoController`; add `mutateGraph`, `undoGraph`, `redoGraph`, `restoreFrom`, `deleteSelected`, `selectAll`, `duplicateSelected`, `copySelectedToClipboard`, `cutSelectedToClipboard`, `pasteFromClipboard`, `frameSelectedOrAll`, `frameAll`, `nodeBounds`; wrap all existing mutators.
- `src/main/kotlin/dev/nitka/nodewire/ui/canvas/CanvasState.kt` — `visibleWidthPx/Height` + `setSize` + `setZoom` + `setPan` + `frameRect`.
- `src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt` — push width/height to `CanvasState.setSize(...)`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` — push `(layoutWidth, layoutHeight)` into `editor.nodeBounds[id]` from `onPositioned`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` — replace Esc-only hotkey with full `EditorKeyBindings.match(...)` fall-through.

---

## Phase 1 — Undo controller + deep copy

### Task 1: `NodeGraph.deepCopy()`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt`

- [ ] **Step 1: Add deepCopy method on NodeGraph**

Append inside the `NodeGraph` class:

```kotlin
    /**
     * Full deep copy via codec round-trip. Cost is a few hundred μs per
     * snapshot at typical sizes; the codec already handles every node
     * config / edge shape correctly, so this is robust against future
     * Node/Edge schema additions.
     */
    fun deepCopy(): NodeGraph {
        val tag = CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, this)
            .result().orElseThrow()
        return CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
            .result().orElseThrow()
    }
```

`CODEC` is the existing companion-object codec on `NodeGraph` (used by `LogicBlockEntity.saveAdditional`).

- [ ] **Step 2: Build (no test yet — it's exercised by Task 2's controller tests)**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt
git commit -m "$(cat <<'EOF'
feat(graph): NodeGraph.deepCopy via codec round-trip

Used by GraphUndoController for snapshot/restore. Robust against
future Node/Edge schema additions since the codec already covers
every field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `GraphUndoController` + tests

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphUndoController.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphUndoControllerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/client/screen/GraphUndoControllerTest.kt
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphUndoControllerTest {

    private val typeKey = ResourceLocation("nodewire", "constant")

    private fun graphWith(count: Int): NodeGraph =
        NodeGraph().apply {
            repeat(count) { i ->
                add(Node(
                    id = Node.newId(),
                    typeKey = typeKey,
                    pos = CanvasPos(i.toFloat() * 10f, 0f),
                ))
            }
        }

    @Test fun `undo with empty stack returns null`() {
        val c = GraphUndoController { 0L }
        assertNull(c.undo(graphWith(0)))
    }

    @Test fun `snapshot then undo returns previous state`() {
        val c = GraphUndoController { 0L }
        val v1 = graphWith(1)
        c.snapshot(v1, mergeable = false)
        val v2 = graphWith(2)
        val restored = c.undo(v2)
        assertNotNull(restored)
        assertEquals(1, restored!!.nodes.size)
    }

    @Test fun `redo replays after undo`() {
        val c = GraphUndoController { 0L }
        val v1 = graphWith(1)
        c.snapshot(v1, mergeable = false)
        val v2 = graphWith(2)
        val afterUndo = c.undo(v2)!!
        val afterRedo = c.redo(afterUndo)
        assertNotNull(afterRedo)
        assertEquals(2, afterRedo!!.nodes.size)
    }

    @Test fun `mergeable snapshots within window overwrite previous`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 100
        c.snapshot(graphWith(2), mergeable = true); now += 100
        c.snapshot(graphWith(3), mergeable = true)
        // Only the most-recent merged snapshot should remain at top of stack.
        val restored = c.undo(graphWith(99))!!
        assertEquals(3, restored.nodes.size)
        // Undo again — should be empty (only one merged entry was pushed).
        assertNull(c.undo(restored))
    }

    @Test fun `non-mergeable push after mergeable breaks the merge`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 100
        c.snapshot(graphWith(2), mergeable = false); now += 100
        c.snapshot(graphWith(3), mergeable = true)
        // Three discrete entries (mergeable-after-non-mergeable does NOT merge).
        assertEquals(3, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(2, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(1, c.undo(graphWith(99))!!.nodes.size)
        assertNull(c.undo(graphWith(99)))
    }

    @Test fun `mergeable snapshot beyond window starts a new entry`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = true); now += 600   // beyond 500ms
        c.snapshot(graphWith(2), mergeable = true)
        assertEquals(2, c.undo(graphWith(99))!!.nodes.size)
        assertEquals(1, c.undo(graphWith(99))!!.nodes.size)
        assertNull(c.undo(graphWith(99)))
    }

    @Test fun `stack capped at 50 entries — oldest dropped`() {
        var now = 0L
        val c = GraphUndoController { now }
        repeat(60) { i ->
            c.snapshot(graphWith(i + 1), mergeable = false); now += 10
        }
        // Pop all 50 (cap) — first 10 were dropped, so we expect snapshots
        // for graphWith(11) through graphWith(60).
        var popped = 0
        while (true) {
            val r = c.undo(graphWith(99)) ?: break
            popped++
            assertTrue(r.nodes.size >= 11)
        }
        assertEquals(50, popped)
    }

    @Test fun `redo cleared after fresh snapshot`() {
        var now = 0L
        val c = GraphUndoController { now }
        c.snapshot(graphWith(1), mergeable = false); now += 100
        c.snapshot(graphWith(2), mergeable = false); now += 100
        c.undo(graphWith(3))  // moves graphWith(3) onto redo
        c.snapshot(graphWith(4), mergeable = false)   // new edit clears redo
        assertNull(c.redo(graphWith(99)))
    }
}
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.GraphUndoControllerTest" -i`
Expected: FAIL — `GraphUndoController` unresolved.

- [ ] **Step 3: Implement `GraphUndoController`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/client/screen/GraphUndoController.kt
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph

/**
 * Snapshot-based undo/redo with a 500ms merge window for continuous
 * operations (drag, slider). Capped at 50 entries — oldest entries
 * dropped silently. Pure data; no Compose state, no MC dependency.
 *
 * Caller passes the current graph each call; controller never holds a
 * reference to the live graph, only to its deep copies.
 */
class GraphUndoController(private val nowMs: () -> Long) {

    private val undoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private val redoStack: ArrayDeque<NodeGraph> = ArrayDeque()
    private var lastPushAt: Long = 0L
    private var lastMergeable: Boolean = false
    private val mergeWindowMs: Long = 500L
    private val cap: Int = 50

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Push [pre] (the state BEFORE a mutation) onto the undo stack.
     * If [mergeable] AND the previous push was also mergeable within
     * [mergeWindowMs], overwrite the top of the stack instead of adding
     * a new entry — drags / slider sweeps collapse to one undo step.
     */
    fun snapshot(pre: NodeGraph, mergeable: Boolean) {
        val now = nowMs()
        val canMerge = mergeable && lastMergeable && (now - lastPushAt) < mergeWindowMs
        val copy = pre.deepCopy()
        if (canMerge && undoStack.isNotEmpty()) {
            undoStack[undoStack.size - 1] = copy
        } else {
            undoStack.addLast(copy)
            if (undoStack.size > cap) undoStack.removeFirst()
        }
        lastPushAt = now
        lastMergeable = mergeable
        redoStack.clear()
    }

    /**
     * Pop the top of the undo stack; push [current] (a deep copy) onto
     * redo. Returns the restored state, or null if nothing to undo.
     */
    fun undo(current: NodeGraph): NodeGraph? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current.deepCopy())
        lastMergeable = false
        return undoStack.removeLast()
    }

    /** Symmetric of [undo]: pop redo top, push current onto undo. */
    fun redo(current: NodeGraph): NodeGraph? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current.deepCopy())
        lastMergeable = false
        return redoStack.removeLast()
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.GraphUndoControllerTest" -i`
Expected: PASS — 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GraphUndoController.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/GraphUndoControllerTest.kt
git commit -m "$(cat <<'EOF'
feat(editor): GraphUndoController — snapshot stacks + merge window

500ms merge window collapses drag/slider sequences into one undo step.
Cap at 50 entries; oldest dropped. Time injected via lambda for tests.
Stateless beyond its two stacks — caller owns the graph.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Clipboard

### Task 3: `GraphClipboard` + tests

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GraphClipboard.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/client/screen/GraphClipboardTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/client/screen/GraphClipboardTest.kt
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphClipboardTest {

    private val typeKey = ResourceLocation("nodewire", "constant")
    private fun node(x: Float) = Node(
        id = Node.newId(),
        typeKey = typeKey,
        pos = CanvasPos(x, 0f),
    )

    @Test fun `encode then decode round-trips node count and positions`() {
        val g = NodeGraph().apply {
            add(node(0f)); add(node(10f)); add(node(20f))
        }
        val raw = GraphClipboard.encode(g)
        val decoded = GraphClipboard.decode(raw)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.nodes.size)
        val xs = decoded.nodes.values.map { it.pos.x }.sorted()
        assertEquals(listOf(0f, 10f, 20f), xs)
    }

    @Test fun `decode of foreign text returns null silently`() {
        assertNull(GraphClipboard.decode("hello world"))
    }

    @Test fun `decode of valid SNBT without marker returns null`() {
        // Looks like our shape but missing the marker boolean.
        val foreign = "{nodes:[],edges:[]}"
        assertNull(GraphClipboard.decode(foreign))
    }

    @Test fun `decode of empty graph round-trips`() {
        val g = NodeGraph()
        val raw = GraphClipboard.encode(g)
        val decoded = GraphClipboard.decode(raw)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.nodes.size)
    }
}
```

- [ ] **Step 2: Run tests, expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.GraphClipboardTest" -i`
Expected: FAIL — `GraphClipboard` unresolved.

- [ ] **Step 3: Implement `GraphClipboard`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/client/screen/GraphClipboard.kt
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser

/**
 * Serialize / parse a subgraph through the system clipboard.
 *
 * Format: a [CompoundTag] with `nodewire_subgraph: 1b` + `version: 1` +
 * `graph: <NodeGraph.CODEC payload>`. Stringified to SNBT for transport.
 * On decode, missing marker → null (foreign clipboard text is silently
 * ignored; Ctrl+V never disturbs other applications' clipboard).
 */
object GraphClipboard {

    private const val MARKER = "nodewire_subgraph"
    private const val VERSION_KEY = "version"
    private const val GRAPH_KEY = "graph"
    private const val CURRENT_VERSION = 1

    /** Build the clipboard string from [graph] (caller passes the filtered subgraph). */
    fun encode(graph: NodeGraph): String {
        val payload = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph)
            .result().orElseThrow()
        val wrapper = CompoundTag().apply {
            putBoolean(MARKER, true)
            putInt(VERSION_KEY, CURRENT_VERSION)
            put(GRAPH_KEY, payload)
        }
        return wrapper.toString()
    }

    /** Parse [raw]. Returns null on any failure (not SNBT, missing marker, codec parse error). */
    fun decode(raw: String): NodeGraph? {
        val parsed = runCatching { TagParser.parseTag(raw) }.getOrNull() ?: return null
        val wrapper = parsed as? CompoundTag ?: return null
        if (!wrapper.getBoolean(MARKER)) return null
        val graphTag = wrapper.get(GRAPH_KEY) ?: return null
        return NodeGraph.CODEC.parse(NbtOps.INSTANCE, graphTag).result().orElse(null)
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.GraphClipboardTest" -i`
Expected: PASS — 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GraphClipboard.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/GraphClipboardTest.kt
git commit -m "$(cat <<'EOF'
feat(editor): GraphClipboard — subgraph SNBT with marker discriminator

encode wraps NodeGraph.CODEC output in {nodewire_subgraph: 1b, version,
graph}; decode silently returns null for foreign clipboard text or for
SNBT without the marker. No need for a separate subgraph format — the
selection filter happens upstream in EditorState.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — `EditorState` integration

### Task 4: Add `mutateGraph` + undo/redo + `restoreFrom`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add the controller field + `mutateGraph` + restore helper**

Add to imports at top of `EditorState.kt`:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```

(If it's already imported — leave as is. Check before adding.)

Inside the `EditorState` class body, near the top (after the `pinPositions` field), add:

```kotlin
    private val undo = GraphUndoController { net.minecraft.Util.getMillis() }

    /**
     * Single entry point for every graph mutation. Snapshots the
     * pre-mutation state, runs [block], then leaves it to the caller's
     * existing flow-update code to refresh per-node Compose state.
     *
     * @param mergeable true for continuous ops (drag, slider) that should
     *   collapse into one undo step within the controller's merge window.
     */
    fun mutateGraph(mergeable: Boolean = false, block: () -> Unit) {
        undo.snapshot(graph, mergeable)
        block()
    }

    fun canUndo(): Boolean = undo.canUndo()
    fun canRedo(): Boolean = undo.canRedo()

    fun undoGraph() {
        val prev = undo.undo(graph) ?: return
        restoreFrom(prev)
    }

    fun redoGraph() {
        val next = undo.redo(graph) ?: return
        restoreFrom(next)
    }

    /**
     * Replace the contents of [graph] with [snapshot] in-place, then resync
     * `nodeFlows` / `_nodes` / `_edges` so existing reactive consumers see
     * the restored state. Selection is cleared because restored ids may
     * not include the currently-selected ones.
     */
    private fun restoreFrom(snapshot: NodeGraph) {
        graph.nodes.clear()
        graph.edges.clear()
        for ((id, n) in snapshot.nodes) graph.nodes[id] = n
        graph.edges.addAll(snapshot.edges)

        val toRemove = nodeFlows.keys - graph.nodes.keys
        for (id in toRemove) nodeFlows.remove(id)
        for ((id, n) in graph.nodes) {
            val flow = nodeFlows[id]
            if (flow == null) nodeFlows[id] = MutableStateFlow(n)
            else flow.value = n
        }
        _nodes.value = graph.nodes.keys.toList()
        _edges.value = graph.edges.toList()
        clearSelection()
    }
```

`undo` field name shadows nothing — verify by searching `\bundo\b` in `EditorState.kt`; if it collides with anything, rename to `undoController`.

- [ ] **Step 2: Build (no test — exercised by integration tests in Phase 5)**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "$(cat <<'EOF'
feat(editor): mutateGraph + undo/redo plumbing on EditorState

Single entry point all graph mutators will route through. restoreFrom
clears the live graph in-place then refills from a snapshot, rebuilds
nodeFlows accordingly, refreshes the _nodes/_edges flows, and clears
selection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Wrap existing graph mutators in `mutateGraph`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

Existing mutators (per `grep -n "fun add\|fun remove\|fun update\|fun change\|fun commit\|fun disconnect\|fun moveSelected\|fun duplicateNode"` at the start of the plan):

- `updateNode` (line ~65)
- `addNode` (line ~72)
- `removeNode` (line ~78)
- `duplicateNode` (line ~91)
- `commitWireTo` (line ~180)
- `changeChannelType` (line ~226)
- `changeLogicGateOp` (line ~242)
- `changeMathConfig` (line ~263)
- `changeCompareType` (line ~288)
- `changeConstantType` (line ~308)
- `changeConvertTypes` (line ~325)
- `changeConvertMode` (line ~344)
- `disconnectAllEdges` (private, ~363)
- `disconnectPin` (line ~371)
- `moveSelectedNodes` (search if not in the grep — should exist around lines 460+)

- [ ] **Step 1: Wrap each mutator**

For every method listed, change its body from doing the mutation directly to delegating through `mutateGraph`. Pattern (mechanical edit per method):

**Before** (e.g. `updateNode`):

```kotlin
fun updateNode(id: NodeId, transform: (Node) -> Node) {
    val flow = nodeFlows[id] ?: return
    val updated = transform(flow.value)
    flow.value = updated
    graph.nodes[id] = updated
}
```

**After:**

```kotlin
fun updateNode(id: NodeId, transform: (Node) -> Node) {
    val flow = nodeFlows[id] ?: return
    mutateGraph(mergeable = true) {
        val updated = transform(flow.value)
        flow.value = updated
        graph.nodes[id] = updated
    }
}
```

**Mergeability rules:**

- `mergeable = true`: `updateNode`, `moveSelectedNodes`.
- `mergeable = false` (default): `addNode`, `removeNode`, `duplicateNode`, `commitWireTo`, `disconnectPin`, `disconnectAllEdges`, every `change*` (channel type / logic op / math / compare / constant / convert).

Apply the wrapping to ALL listed methods. For `disconnectAllEdges` (private) wrap too — it's called from at least one removal flow and we want consistent undo behaviour.

**Important:** don't wrap mutators that are themselves called inside another wrapper. Check:
- `removeNode` does NOT currently call `disconnectAllEdges` from what we saw — verify by reading line 78. If it does, leave the inner call un-wrapped to avoid double-snapshot.

- [ ] **Step 2: Build + spot-check existing UI still works**

Run: `./gradlew build && ./gradlew test`
Expected: SUCCESS + all existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "$(cat <<'EOF'
refactor(editor): route all graph mutators through mutateGraph

addNode / removeNode / updateNode / duplicateNode / commitWireTo /
disconnectPin / disconnectAllEdges / moveSelectedNodes / all change*
methods now snapshot before mutation. updateNode + moveSelectedNodes
push mergeable=true so a drag or slider sweep collapses into one
undo step.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Selection ops + paste + frame methods

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeBounds.kt`

- [ ] **Step 1: Create `NodeBounds`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/client/screen/NodeBounds.kt
package dev.nitka.nodewire.client.screen

/**
 * Last-known layout size of a node card on the canvas. Published by
 * [NodeCard.onPositioned] and consumed by frame/fit operations on
 * [EditorState]. Width/height are pixels in canvas-world space (i.e.
 * the post-yoga layout size; multiply by canvas zoom for screen px).
 */
data class NodeBounds(val width: Int, val height: Int)
```

- [ ] **Step 2: Add `nodeBounds` + selection / paste / frame methods on `EditorState`**

Add to imports if missing:

```kotlin
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.ui.canvas.CanvasState
import java.util.UUID
```

Inside the class:

```kotlin
    /** Per-card bounds, written by `NodeCard.onPositioned`, read by frame/fit. */
    val nodeBounds: SnapshotStateMap<NodeId, NodeBounds> =
        androidx.compose.runtime.mutableStateMapOf()

    /** Canvas state hooked up from `NodeEditorScreen.Content` so framing can adjust pan/zoom. */
    var canvasState: CanvasState? = null

    fun selectAll() {
        selectedNodes = graph.nodes.keys.toSet()
    }

    fun deleteSelected() {
        if (selectedNodes.isEmpty()) return
        val ids = selectedNodes.toList()
        mutateGraph {
            for (id in ids) {
                graph.nodes.remove(id)
                nodeFlows.remove(id)
            }
            graph.edges.removeAll { it.from.node in ids || it.to.node in ids }
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
        }
        clearSelection()
    }

    fun duplicateSelected() {
        if (selectedNodes.isEmpty()) return
        val sources = selectedNodes.mapNotNull { graph.nodes[it] }
        if (sources.isEmpty()) return
        val newIds = mutableListOf<NodeId>()
        mutateGraph {
            for (src in sources) {
                val copy = Node(
                    id = Node.newId(),
                    typeKey = src.typeKey,
                    pos = CanvasPos(src.pos.x + DUPLICATE_OFFSET, src.pos.y + DUPLICATE_OFFSET),
                    inputs = src.inputs,
                    outputs = src.outputs,
                    config = src.config.copy(),
                )
                graph.add(copy)
                nodeFlows[copy.id] = MutableStateFlow(copy)
                newIds.add(copy.id)
            }
            _nodes.value = graph.nodes.keys.toList()
        }
        selectMany(newIds)
    }

    fun copySelectedToClipboard() {
        if (selectedNodes.isEmpty()) return
        val sub = NodeGraph().apply {
            for (n in graph.nodes.values) if (n.id in selectedNodes) add(n)
            for (e in graph.edges) {
                if (e.from.node in selectedNodes && e.to.node in selectedNodes) edges.add(e)
            }
        }
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard =
            GraphClipboard.encode(sub)
    }

    fun cutSelectedToClipboard() {
        if (selectedNodes.isEmpty()) return
        copySelectedToClipboard()
        deleteSelected()
    }

    /**
     * Decode clipboard SNBT and paste at world ([cursorWorldX], [cursorWorldY]).
     * Regenerates all UUIDs, drops nodes whose typeKey is no longer registered,
     * drops edges that reference dropped nodes. Selects pasted nodes.
     * Silent no-op if clipboard isn't our format.
     */
    fun pasteFromClipboard(cursorWorldX: Float, cursorWorldY: Float) {
        val raw = net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard ?: return
        val sub = GraphClipboard.decode(raw) ?: return

        val idMap = HashMap<NodeId, NodeId>()
        val newNodes = sub.nodes.values.mapNotNull { old ->
            if (NodeTypeRegistry.get(old.typeKey) == null) return@mapNotNull null
            val newId = Node.newId()
            idMap[old.id] = newId
            old.copy(id = newId)
        }
        if (newNodes.isEmpty()) return

        val cx = newNodes.map { it.pos.x }.average().toFloat()
        val cy = newNodes.map { it.pos.y }.average().toFloat()
        val dx = cursorWorldX - cx
        val dy = cursorWorldY - cy
        val translated = newNodes.map {
            it.copy(pos = CanvasPos(it.pos.x + dx, it.pos.y + dy))
        }

        val newEdges = sub.edges.mapNotNull { e ->
            val newFromId = idMap[e.from.node] ?: return@mapNotNull null
            val newToId = idMap[e.to.node] ?: return@mapNotNull null
            e.copy(
                from = e.from.copy(node = newFromId),
                to = e.to.copy(node = newToId),
            )
        }

        mutateGraph {
            for (n in translated) {
                graph.add(n)
                nodeFlows[n.id] = MutableStateFlow(n)
            }
            graph.edges.addAll(newEdges)
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
        }
        selectMany(translated.map { it.id })
    }

    fun frameSelectedOrAll() {
        val ids = if (selectedNodes.isNotEmpty()) selectedNodes else graph.nodes.keys
        frameNodes(ids)
    }

    fun frameAll() = frameNodes(graph.nodes.keys)

    private fun frameNodes(ids: Set<NodeId>) {
        if (ids.isEmpty()) return
        val canvas = canvasState ?: return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (id in ids) {
            val n = graph.nodes[id] ?: continue
            val b = nodeBounds[id] ?: NodeBounds(120, 60)
            minX = minOf(minX, n.pos.x); minY = minOf(minY, n.pos.y)
            maxX = maxOf(maxX, n.pos.x + b.width); maxY = maxOf(maxY, n.pos.y + b.height)
        }
        canvas.frameRect(minX, minY, maxX, maxY)
    }
```

`DUPLICATE_OFFSET` — search `EditorState.kt` for its definition (it's a constant near the bottom; if not, set it to `30f`).

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD FAILS — `CanvasState.frameRect` doesn't exist yet. That's Task 7. Skip the build verification here and run after Task 7 lands.

Actually — to keep build green at every commit, comment out the `frameSelectedOrAll/frameAll/frameNodes` methods and the `canvasState` field for now (they reference `frameRect` which doesn't exist). Then add them back in Task 7. OR reorder: implement Task 7 first, then this. **Reorder is cleaner — do Task 7 before completing Task 6's frame methods.**

Implementer note: split Task 6 into:
- **Task 6a:** `NodeBounds.kt` + selection ops + paste (no frame methods). Build green; commit.
- **Task 6b:** Frame methods + `canvasState` field. Done in Phase 4 after Task 7.

- [ ] **Step 4: Commit (Task 6a — without frame methods)**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeBounds.kt
git commit -m "$(cat <<'EOF'
feat(editor): selection ops + paste + nodeBounds field

deleteSelected, selectAll, duplicateSelected, copy/cut/paste through
GraphClipboard. UUIDs regenerated and edges rewritten on paste.
nodeBounds field will be populated by NodeCard.onPositioned (next
task) and read by frame/fit (which lands after CanvasState.frameRect).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — CanvasState + frame helpers + NodeCard wiring

### Task 7: `CanvasState.setSize` + `setZoom` + `setPan` + `frameRect`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/canvas/CanvasState.kt`

- [ ] **Step 1: Add new state + methods**

Inside the `CanvasState` class, after the existing `originX/Y` state:

```kotlin
    private var _visibleWidthPx by mutableStateOf(0)
    private var _visibleHeightPx by mutableStateOf(0)
    val visibleWidthPx: Int get() = _visibleWidthPx
    val visibleHeightPx: Int get() = _visibleHeightPx

    /** Push the current canvas size from `NodeCanvas.onSizeChanged`. */
    fun setSize(w: Int, h: Int) {
        _visibleWidthPx = w
        _visibleHeightPx = h
    }

    /** Set zoom directly (clamped); for frame-fit operations. */
    fun setZoom(z: Float) { _zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** Set pan directly (world units); for frame-fit operations. */
    fun setPan(x: Float, y: Float) { _panX = x; _panY = y }

    /**
     * Fit the given world-AABB into the visible viewport with a [marginPx]
     * gutter on each side. Picks the smaller of the per-axis zoom-fits
     * (clamped to [MIN_ZOOM]/[MAX_ZOOM]) and centres the AABB midpoint at
     * the viewport centre.
     *
     * No-op if the viewport size hasn't been pushed yet, or if the AABB
     * has non-positive width/height.
     */
    fun frameRect(minX: Float, minY: Float, maxX: Float, maxY: Float, marginPx: Int = 32) {
        val viewW = _visibleWidthPx; val viewH = _visibleHeightPx
        if (viewW <= 0 || viewH <= 0) return
        val w = maxX - minX; val h = maxY - minY
        if (w <= 0f || h <= 0f) return
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

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/canvas/CanvasState.kt
git commit -m "$(cat <<'EOF'
feat(canvas): setSize / setZoom / setPan / frameRect on CanvasState

Absolute setters complement the existing panBy/zoomBy delta API. frameRect
fits a world-AABB into the visible viewport with a margin; minor maths
to centre the midpoint at viewport centre while applying the chosen
zoom. Used by EditorState.frameSelectedOrAll (next task).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Wire `NodeCanvas.onSizeChanged` to `CanvasState.setSize`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt`

- [ ] **Step 1: Read current NodeCanvas to find the right anchor**

```bash
grep -n "onPositioned\|setOrigin\|onSizeChanged\|Layout\\(" src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt
```

The existing `onPositioned { c -> state.setOrigin(c.screenX, c.screenY) }` is the place to also publish size. `LayoutCoordinates` has `width` and `height` fields.

- [ ] **Step 2: Extend the existing onPositioned**

Inside `NodeCanvas`, find the modifier chain that calls `state.setOrigin(...)`. Update the lambda body:

```kotlin
.onPositioned { coords ->
    state.setOrigin(coords.screenX, coords.screenY)
    state.setSize(coords.width, coords.height)
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt
git commit -m "$(cat <<'EOF'
feat(canvas): NodeCanvas publishes its size to CanvasState

Same onPositioned callback that already publishes origin now also calls
setSize(width, height). Frame/fit reads visibleWidthPx/Height; without
this the AABB-to-viewport math has nothing to fit against.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: NodeCard publishes bounds to `editor.nodeBounds`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt`

- [ ] **Step 1: Find the existing onPositioned on the card root**

Search:

```bash
grep -n "onPositioned\|pinPositions\.set\|editor\\." src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt | head -20
```

There's already an onPositioned (used for pin positioning). Extend it.

- [ ] **Step 2: Add bounds publish in the existing onPositioned lambda**

Inside the card root's `onPositioned { coords -> … }`, add:

```kotlin
editor?.nodeBounds?.set(node.id, NodeBounds(coords.width, coords.height))
```

(Assumes there's a `node` local in scope — there is, since it's a NodeCard. If `editor` is non-nullable in this context, drop the `?.`.)

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt
git commit -m "$(cat <<'EOF'
feat(editor): NodeCard publishes (width, height) to editor.nodeBounds

Same onPositioned that updates pin positions now also writes the card's
layout size into editor.nodeBounds[id], so frame/fit can compute the
AABB of selected nodes from real card sizes (with a 120×60 fallback
for the first frame before layout has measured).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Complete Task 6's deferred frame methods

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`

- [ ] **Step 1: Add frame methods to `EditorState`**

Append inside the class (now that `CanvasState.frameRect` exists):

```kotlin
    /** Hooked up from `NodeEditorScreen.Content` so frame methods can adjust pan/zoom. */
    var canvasState: dev.nitka.nodewire.ui.canvas.CanvasState? = null

    fun frameSelectedOrAll() {
        val ids = if (selectedNodes.isNotEmpty()) selectedNodes else graph.nodes.keys
        frameNodes(ids)
    }

    fun frameAll() = frameNodes(graph.nodes.keys)

    private fun frameNodes(ids: Set<NodeId>) {
        if (ids.isEmpty()) return
        val canvas = canvasState ?: return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (id in ids) {
            val n = graph.nodes[id] ?: continue
            val b = nodeBounds[id] ?: NodeBounds(120, 60)
            minX = minOf(minX, n.pos.x); minY = minOf(minY, n.pos.y)
            maxX = maxOf(maxX, n.pos.x + b.width); maxY = maxOf(maxY, n.pos.y + b.height)
        }
        canvas.frameRect(minX, minY, maxX, maxY)
    }
```

- [ ] **Step 2: Wire `canvasState` from `NodeEditorScreen.Content`**

Read `NodeEditorScreen.kt` near `Content`. Locate where `CanvasState` is constructed (likely via `rememberCanvasState()`). Pass it to the editor:

```kotlin
val canvasState = rememberCanvasState()
val editor = remember { EditorState(graph, pos) }
editor.canvasState = canvasState
```

Add this line right after `EditorState` is created. If the construction is already inside an `LaunchedEffect` / `remember` block, place the assignment immediately after.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "$(cat <<'EOF'
feat(editor): frameSelectedOrAll + frameAll on EditorState

Computes the AABB of the chosen ids using node.pos + nodeBounds (with
120×60 fallback), then asks CanvasState.frameRect to fit it. Wired
from NodeEditorScreen.Content via canvasState ref on the editor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — Editor key bindings + dispatch

### Task 11: `EditorKeyBindings` table

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt`

- [ ] **Step 1: Implement table + match**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt
package dev.nitka.nodewire.client.screen

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.ui.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Editor-level keyboard shortcuts. Dispatched from
 * [NodeEditorScreen.keyPressed] only when no TextInput holds focus.
 *
 * Mirror of [dev.nitka.nodewire.ui.input.text.TextFieldKeyBindings] in
 * style — data-driven so the table is greppable and the dispatcher is
 * trivial.
 *
 * `action` takes an [EditorState] and a `cursorWorldX/Y` pair so
 * paste-at-cursor knows where to land; non-paste actions ignore it.
 */
data class EditorKeyBinding(
    val keyCode: Int,
    val modifiers: Int = 0,
    val action: (editor: EditorState, cursorWorldX: Float, cursorWorldY: Float) -> Boolean,
)

object EditorKeyBindings {
    private const val MOD_MASK = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT or
        GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER

    val DEFAULT: List<EditorKeyBinding> = listOf(
        // Delete / clear-selection
        EditorKeyBinding(InputConstants.KEY_DELETE)                                  { e, _, _ -> e.deleteSelected(); true },
        EditorKeyBinding(InputConstants.KEY_BACKSPACE)                               { e, _, _ -> e.deleteSelected(); true },
        // Select all + duplicate
        EditorKeyBinding(InputConstants.KEY_A, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.selectAll(); true },
        EditorKeyBinding(InputConstants.KEY_D, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.duplicateSelected(); true },
        // Clipboard
        EditorKeyBinding(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.copySelectedToClipboard(); true },
        EditorKeyBinding(InputConstants.KEY_X, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.cutSelectedToClipboard(); true },
        EditorKeyBinding(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL)                { e, cx, cy -> e.pasteFromClipboard(cx, cy); true },
        // Undo / redo
        EditorKeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.undoGraph(); true },
        EditorKeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { e, _, _ -> e.redoGraph(); true },
        EditorKeyBinding(InputConstants.KEY_Y, GLFW.GLFW_MOD_CONTROL)                { e, _, _ -> e.redoGraph(); true },
        // Framing
        EditorKeyBinding(InputConstants.KEY_F)                                       { e, _, _ -> e.frameSelectedOrAll(); true },
        EditorKeyBinding(InputConstants.KEY_F, GLFW.GLFW_MOD_SHIFT)                  { e, _, _ -> e.frameAll(); true },
        // Esc — handled with priority: close menu > clear selection > pass through
        EditorKeyBinding(InputConstants.KEY_ESCAPE) { e, _, _ ->
            when {
                e.contextMenu != null -> { e.closeContextMenu(); true }
                e.selectedNodes.isNotEmpty() -> { e.clearSelection(); true }
                else -> false
            }
        },
    )

    fun match(bindings: List<EditorKeyBinding>, event: KeyEvent.Press): EditorKeyBinding? {
        val mods = event.modifiers and MOD_MASK
        return bindings.firstOrNull { it.keyCode == event.keyCode && it.modifiers == mods }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt
git commit -m "$(cat <<'EOF'
feat(editor): EditorKeyBindings — data-driven shortcut table

13 bindings: Delete/Backspace (delete selected), Ctrl+A (select all),
Ctrl+D (duplicate), Ctrl+C/X/V (clipboard), Ctrl+Z/Y/Shift+Z (undo/
redo), F (frame selected), Shift+F (frame all), Esc (close menu →
clear selection → pass through). Action lambdas receive cursor world
coords so paste-at-cursor knows where to land.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Dispatch from `NodeEditorScreen.keyPressed`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`

Existing override (line ~65–74):

```kotlin
override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
    if (keyCode == 256 /* GLFW_KEY_ESCAPE */) {
        val e = editorRef
        if (e != null && e.selectedNodes.isNotEmpty()) {
            e.clearSelection()
            return true
        }
    }
    return super.keyPressed(keyCode, scanCode, modifiers)
}
```

- [ ] **Step 1: Replace with full dispatcher**

```kotlin
override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
    // First, let any focused TextInput (or other key handler) consume.
    if (super.keyPressed(keyCode, scanCode, modifiers)) return true

    // If a TextInput is still focused but didn't consume this specific
    // key (e.g. F while typing), don't hijack — it should pass through.
    if (owner.keyFocus != null) return false

    val editor = editorRef ?: return false
    val event = dev.nitka.nodewire.ui.input.KeyEvent.Press(keyCode, scanCode, modifiers)
    val binding = EditorKeyBindings.match(EditorKeyBindings.DEFAULT, event) ?: return false

    // Cursor world coords (for paste-at-cursor). Read from the canvas
    // state's last-known mouse position; fallback to view centre.
    val canvas = editor.canvasState
    val cursorWorldX: Float = canvas?.cursorWorldX ?: 0f
    val cursorWorldY: Float = canvas?.cursorWorldY ?: 0f

    return binding.action(editor, cursorWorldX, cursorWorldY)
}
```

Two issues to resolve:

1. **`owner.keyFocus` access** — `NwUiOwner` has a `keyFocus: KeyHandler?` field. Make sure it's public (read-only). Find:

   ```bash
   grep -n "keyFocus" src/main/kotlin/dev/nitka/nodewire/ui/core/NwUiOwner.kt
   ```

   If it's private, add a public read-only accessor `val keyFocusOwner: KeyHandler? get() = keyFocus`. Or expose `fun hasKeyFocus(): Boolean = keyFocus != null` and use that.

2. **`canvas.cursorWorldX/Y`** — `CanvasState` doesn't currently track cursor world coords. Quick add: a pair of state fields written by `NodeCanvas.pointerInput` on every `PointerEvent.Move` after converting screen to world. Add to `CanvasState`:

   ```kotlin
   private var _cursorWorldX by mutableStateOf(0f)
   private var _cursorWorldY by mutableStateOf(0f)
   val cursorWorldX: Float get() = _cursorWorldX
   val cursorWorldY: Float get() = _cursorWorldY
   fun setCursorWorld(x: Float, y: Float) { _cursorWorldX = x; _cursorWorldY = y }
   ```

   And inside `NodeCanvas` pointerInput Move-event handler:

   ```kotlin
   is PointerEvent.Move -> {
       val z = state.zoom; val px = state.panX; val py = state.panY
       state.setCursorWorld(ev.x / z - px, ev.y / z - py)
       false
   }
   ```

   (Coordinate transform: `screen = (world + pan) * zoom` → `world = screen/zoom - pan`. Verify against the existing canvas hit-test code; mirror its sign convention.)

   If `NodeCanvas` already has a pointer-handler with similar transform — extend it; don't add a second.

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: SUCCESS.

If `owner.keyFocus` private — fix the accessor in `NwUiOwner.kt` and rebuild.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/canvas/CanvasState.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/canvas/NodeCanvas.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/core/NwUiOwner.kt
git commit -m "$(cat <<'EOF'
feat(editor): wire EditorKeyBindings into NodeEditorScreen.keyPressed

Fall-through dispatch: super first (focused TextInput consumes),
then bail if any TextInput still focused (don't hijack typing), then
match against EditorKeyBindings.DEFAULT. Paste-at-cursor reads world
coords from CanvasState which now tracks the mouse on every Move.
NwUiOwner exposes keyFocus as a read-only accessor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6 — Final validation

### Task 13: Full test + manual handoff

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS (12 new + existing).

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Hand off manual test plan**

**Do NOT run `./gradlew runClient`.** Report the following list to the user:

1. **Delete**: place 3 nodes via right-click menu; select 2 (Shift-click or rubber-band); press `Delete` → one remains; connected wires gone. `Backspace` does the same.
2. **Ctrl+Z** restores the 3 nodes + wires. `Ctrl+Y` re-deletes.
3. **Ctrl+A** selects every node.
4. **Ctrl+D** duplicates selected at +30/+30 offset; duplicates are selected.
5. **Ctrl+C + Ctrl+V**: copy a 2-node selection → click in empty area → paste → 2 new nodes centred at cursor with fresh UUIDs.
6. **Ctrl+X**: same as Ctrl+C but originals removed.
7. **Drag-then-Ctrl+Z**: drag a 5-node selection 200px → single Ctrl+Z restores all positions.
8. **Type-then-Ctrl+Z while a TextInput is focused**: TextInput's own undo fires, graph untouched. Click outside the input → Ctrl+Z → graph reverts.
9. **F with selection**: canvas centres + zooms to fit selected nodes. **F without selection**: frames all. **Shift+F**: always frames all.
10. **Esc** with open context menu → closes menu. Esc again with non-empty selection → clears selection. Esc again with nothing → screen closes (vanilla pass-through).
11. **Cross-block paste**: copy in LogicBlock A, close, open LogicBlock B, Ctrl+V → subgraph appears.
12. **Foreign clipboard**: copy text in another app, press Ctrl+V → silent no-op, no crash.
13. **50-op undo cap**: do >50 discrete edits → oldest undo entries silently dropped; redo only goes back through what's still on the stack.

---

## Out of scope (separate sub-projects)

- Quick-spawn palette (Tab → fuzzy search).
- Wire utilities (reroute knots, Alt-click disconnect, drag-pin-into-empty).
- Visual organisation (frames/groups, sticky comments, color tags, mute).
- Navigation polish (minimap, snap-grid, pan with middle-mouse).
- Tooltips / status badges / F2-rename / Ctrl-drag duplicate.
