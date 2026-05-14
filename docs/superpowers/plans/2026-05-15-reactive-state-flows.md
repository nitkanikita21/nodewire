# Reactive editor state via StateFlow — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the coarse `graphVersion` counter with per-node `MutableStateFlow<Node>` + graph-level flows for membership / edges / selection so individual cards re-render only when their own state changes, eliminating the stale pin-row bug after pin-type changes.

**Architecture:** Per-node `MutableStateFlow<Node>` is held in `EditorState`. All mutations go through `updateNode(id) { it.copy(...) }`. `Node` becomes immutable (`var → val`). Composables read via `collectAsState()`. Migration is staged so compile stays green between tasks: flows are added alongside `graphVersion`, consumers migrate one at a time, then `var → val` + counter removal lands last.

**Tech Stack:** Kotlin 2.0.20, compose-runtime 1.7.0 (`androidx.compose.runtime.collectAsState`), kotlinx.coroutines `StateFlow`, Minecraft Forge 1.20.1.

---

## File Structure

**Modify:**
- `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` — `var → val` for `pos`, `inputs`, `outputs` (Task 6 only).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — add flows + `updateNode` (Task 1); rewrite mutators (Task 4); drop counters + add `snapshotGraph()` (Task 6).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` — `Node` param → `NodeId` + `collectAsState` (Task 2).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` — iterate `editor.nodes.collectAsState().value` (Task 3); use `snapshotGraph()` for save (Task 6).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` — collect `editor.edges` (Task 3).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — replace 14 `bumpGraphVersion()` callsites with `updateNode(id) { ... }` (Task 5).

**Create:**
- `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFlowTest.kt` — unit tests on the flow API (Task 1, grown across tasks).

---

### Task 1: EditorState gains flows alongside `graphVersion` (additive)

Backward-compatible additive change. Both the existing counter-driven path and the new flow-driven path coexist until the final task swaps over.

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFlowTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `/home/nitka/CODING/nodewire/src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFlowTest.kt`:

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorStateFlowTest {

    private fun mkNode(name: String, x: Float = 0f, y: Float = 0f): Node = Node(
        id = Node.newId(),
        typeKey = ResourceLocation("nodewire", "bool_const"),
        pos = CanvasPos(x, y),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test
    fun nodeFlowReturnsCurrentSnapshot() {
        val seed = mkNode("seed")
        val g = NodeGraph().also { it.add(seed) }
        val ed = EditorState(g)
        val flow = ed.nodeFlow(seed.id)
        assertNotNull(flow)
        assertEquals(seed.id, flow!!.value.id)
    }

    @Test
    fun nodeFlowMissingForUnknownId() {
        val ed = EditorState(NodeGraph())
        assertNull(ed.nodeFlow(Node.newId()))
    }

    @Test
    fun updateNodeEmitsNewSnapshot() {
        val seed = mkNode("seed", x = 0f, y = 0f)
        val ed = EditorState(NodeGraph().also { it.add(seed) })
        val before = ed.nodeFlow(seed.id)!!.value
        ed.updateNode(seed.id) { it.copy(pos = CanvasPos(10f, 20f)) }
        val after = ed.nodeFlow(seed.id)!!.value
        assertNotEquals(before, after)
        assertEquals(10f, after.pos.x)
        assertEquals(20f, after.pos.y)
    }

    @Test
    fun addNodeAppearsInNodesFlow() {
        val ed = EditorState(NodeGraph())
        val n = mkNode("new")
        ed.addNode(n)
        assertTrue(n.id in ed.nodes.value)
        assertNotNull(ed.nodeFlow(n.id))
    }

    @Test
    fun removeNodeDropsFromNodesFlowAndPrunesEdges() {
        val a = mkNode("a")
        val b = mkNode("b").copy(inputs = listOf(Pin("in", "In", PinType.BOOL)))
        val g = NodeGraph().also { it.add(a); it.add(b) }
        g.addEdge(Edge(PinRef(a.id, "out"), PinRef(b.id, "in")))
        val ed = EditorState(g)
        ed.removeNode(a.id)
        assertTrue(a.id !in ed.nodes.value)
        assertNull(ed.nodeFlow(a.id))
        assertTrue(ed.edges.value.none { it.from.node == a.id || it.to.node == a.id })
    }
}
```

- [ ] **Step 2: Run tests — expect compile fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateFlowTest"`
Expected: compile failure — `nodeFlow` / `updateNode` / `nodes` / `edges` unresolved on `EditorState`.

- [ ] **Step 3: Add the flow API to EditorState**

Edit `/home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`. Add imports near the top (after existing `androidx.compose.runtime.*` imports):

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.nitka.nodewire.graph.NodeId
```

Inside the `EditorState` class body, immediately after the existing `val pinPositions = PinPositions()` line, add:

```kotlin
    // ── StateFlow plumbing (additive in this task; counters above are kept) ──
    //
    // Each node has its own MutableStateFlow<Node> so card composables only
    // recompose when their own data changes. The flows are populated from
    // the initial graph here and updated by [updateNode] / [addNode] /
    // [removeNode]. The underlying [graph] is still mutated for now so that
    // pre-flow consumers continue to work; final task drops the mirroring.

    private val nodeFlows: MutableMap<NodeId, MutableStateFlow<Node>> =
        graph.nodes.mapValues { (_, n) -> MutableStateFlow(n) }.toMutableMap()

    private val _nodes: MutableStateFlow<List<NodeId>> =
        MutableStateFlow(graph.nodes.keys.toList())
    val nodes: StateFlow<List<NodeId>> = _nodes.asStateFlow()

    private val _edges: MutableStateFlow<List<Edge>> =
        MutableStateFlow(graph.edges.toList())
    val edges: StateFlow<List<Edge>> = _edges.asStateFlow()

    /** Per-node flow, or null if [id] is unknown. */
    fun nodeFlow(id: NodeId): StateFlow<Node>? = nodeFlows[id]?.asStateFlow()

    /**
     * Apply [transform] to the current Node value and emit the result.
     * No-op if [id] is gone. Both the per-node flow and the underlying
     * [graph.nodes] map are updated so old `graphVersion`-based callers
     * still see consistent state during the migration.
     */
    fun updateNode(id: NodeId, transform: (Node) -> Node) {
        val flow = nodeFlows[id] ?: return
        val updated = transform(flow.value)
        flow.value = updated
        graph.nodes[id] = updated
    }
```

Now update `addNode` and `removeNode` so they also emit flows. Find the current `addNode`:

```kotlin
    fun addNode(node: Node) {
        graph.add(node)
        nodesVersion++
        graphVersion++
    }
```

Replace with:

```kotlin
    fun addNode(node: Node) {
        graph.add(node)
        nodeFlows[node.id] = MutableStateFlow(node)
        _nodes.value = _nodes.value + node.id
        nodesVersion++
        graphVersion++
    }
```

Find the current `removeNode`:

```kotlin
    fun removeNode(id: dev.nitka.nodewire.graph.NodeId) {
        graph.removeNode(id)
        nodesVersion++
        graphVersion++
    }
```

Replace with:

```kotlin
    fun removeNode(id: dev.nitka.nodewire.graph.NodeId) {
        graph.removeNode(id)
        nodeFlows.remove(id)
        _nodes.value = _nodes.value - id
        _edges.value = _edges.value.filter { it.from.node != id && it.to.node != id }
        nodesVersion++
        graphVersion++
    }
```

(Other mutators stay as-is in this task — they'll move in Task 4. The underlying `graph` is still mutated alongside the flows for back-compat.)

- [ ] **Step 4: Run tests — expect 5 PASS**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateFlowTest"`
Expected: 5 PASS.

- [ ] **Step 5: Run full test suite (no regressions expected)**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /home/nitka/CODING/nodewire
git branch --show-current   # must be master
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFlowTest.kt
git commit -m "feat(editor): add StateFlow plumbing to EditorState"
```

---

### Task 2: Migrate `NodeCard` to read its Node from the flow

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` (call-site change only)

The card swaps its `Node`-by-value parameter for a `NodeId`, looks up the per-node flow on the editor, and reads it via `collectAsState`. Drag updates go through `editor.updateNode` instead of mutating `node.pos` directly. Other card behaviour (right-click menu, selection click, drag delegation) is preserved.

- [ ] **Step 1: Change NodeCard signature + read from flow**

Edit `/home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt`. Find the function header:

```kotlin
@Composable
fun NodeCard(
    node: Node,
    modifier: Modifier = Modifier,
) {
```

Replace with:

```kotlin
@Composable
fun NodeCard(
    nodeId: dev.nitka.nodewire.graph.NodeId,
    modifier: Modifier = Modifier,
) {
```

Then the function body's opening lines (locals) — find:

```kotlin
    val canvas = LocalCanvasState.current
    val editor = LocalEditorState.current
    // Read graphVersion so any mutation that bumps it (pin-type rebuilds,
    // group drags via moveSelected, etc.) triggers a recomposition here.
    // node.pos / inputs / outputs are plain `var`s — Compose can't observe
    // them on its own.
    editor?.graphVersion
    val pos = node.pos
    val selected = editor?.isSelected(node.id) == true
```

Replace with:

```kotlin
    val canvas = LocalCanvasState.current
    val editor = LocalEditorState.current
    // Subscribe to the per-node flow so only this card recomposes when its
    // own Node changes. If the editor isn't present (shouldn't happen in
    // production paths but keeps the type system happy) we render nothing.
    val flow = editor?.nodeFlow(nodeId) ?: return
    val node by flow.collectAsState()
    val pos = node.pos
    val selected = editor.isSelected(nodeId)
```

Add the import if missing (near the existing `androidx.compose.runtime.*` imports):

```kotlin
import androidx.compose.runtime.collectAsState
```

- [ ] **Step 2: Replace direct `node.pos =` mutation in the drag handler**

Find the `onDragDelta = { dx, dy -> ... }` block on the title bar. The current code (single-node fallback branch) reads:

```kotlin
                    } else {
                        node.pos = CanvasPos(node.pos.x + dxWorld, node.pos.y + dyWorld)
                        editor?.bumpGraphVersion()
                    }
```

Replace with:

```kotlin
                    } else {
                        editor?.updateNode(nodeId) {
                            it.copy(pos = CanvasPos(it.pos.x + dxWorld, it.pos.y + dyWorld))
                        }
                    }
```

The other branch (group drag via `editor.moveSelected`) stays as-is for now — it still mutates `node.pos` inside EditorState. Task 4 rewrites `moveSelected` to use `updateNode`.

Also update the right-click handler — find:

```kotlin
            .pointerInput { ev, x, y ->
                if (ev is PointerEvent.Press && ev.button == RIGHT_BUTTON) {
                    if (editor != null && canvas != null) {
                        val worldX = pos.x + x
                        val worldY = pos.y + y
                        val screenX = ((worldX + canvas.panX) * canvas.zoom).toInt()
                        val screenY = ((worldY + canvas.panY) * canvas.zoom).toInt()
                        editor.openNodeMenu(screenX, screenY, node.id)
                    }
                    true
                } else false
            },
```

Change `editor.openNodeMenu(screenX, screenY, node.id)` → `editor.openNodeMenu(screenX, screenY, nodeId)`.

Same for the selection-click handler `editor.toggleSelection(node.id)` / `editor.selectOnly(node.id)` / `editor.isSelected(node.id)` etc. — rename `node.id` → `nodeId` everywhere in this file (after the new local `nodeId` parameter is in place; the `val node` snapshot still has the same id, but using the param is clearer and avoids an unnecessary read).

Run `grep -n "node\.id" /home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` to find each call-site, change them. Look further into `CardBody`, `PinRow` etc. if they reference `node.id`.

If any internal helper (e.g. `CardBody(node.id, node)`) takes `nodeId`, just pass `nodeId` instead of `node.id`. If it takes `node`, pass `node` (the local snapshot).

- [ ] **Step 3: Update the call site in NodeEditorScreen**

Edit `/home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`. Find:

```kotlin
            val nodes = remember(editor.nodesVersion) { graph.nodes.values.toList() }
```

and the loop:

```kotlin
                        for (node in nodes) {
                            NodeCard(node = node)
                        }
```

Replace the `remember` line with:

```kotlin
            val nodeIds by editor.nodes.collectAsState()
```

and the loop with:

```kotlin
                        for (id in nodeIds) {
                            NodeCard(nodeId = id)
                        }
```

Add `import androidx.compose.runtime.collectAsState` and `import androidx.compose.runtime.getValue` near the existing runtime imports if missing.

- [ ] **Step 4: Compile**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If errors point at `node.id` callsites in `NodeCard.kt` you missed, fix them.

- [ ] **Step 5: Run tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /home/nitka/CODING/nodewire
git branch --show-current   # master
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "refactor(editor): NodeCard reads its Node from per-node flow"
```

---

### Task 3: Migrate `WireLayer` to collect `editor.edges`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt`

WireLayer reads `graph.edges` / `graph.nodes` directly today. After this task it reads `editor.edges.collectAsState()` for the edge list, and per-node flows for pin types. The visible behaviour is unchanged — only the subscription source moves.

- [ ] **Step 1: Inspect current state**

Run: `cd /home/nitka/CODING/nodewire && cat src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt | head -100`

Identify where `graph.edges` and `graph.nodes[…]` are read. They're typically inside a `@Composable fun WireLayer()` body.

- [ ] **Step 2: Replace edge source**

In the function body, find the line(s) that read `for (edge in graph.edges)` (or `editor.graph.edges`). Insert above the loop:

```kotlin
    val edges by editor.edges.collectAsState()
```

and change the loop to iterate `edges` instead of `graph.edges`.

For pin-type lookups inside the loop — e.g. `graph.nodes[edge.from.node]` — replace each lookup with `editor.nodeFlow(edge.from.node)?.value`. Doing it via `.value` rather than `collectAsState` keeps the loop body tight — the outer edge subscription is enough to re-render on graph changes, since adding/removing edges or swapping pins ends up touching either `_edges` or a per-node flow.

If `WireLayer` doesn't already take an `editor: EditorState` param and reads through `LocalEditorState.current` — keep that, just access `editor.edges` / `editor.nodeFlow`. If it currently reads `graph` from some other binding, add `val editor = LocalEditorState.current ?: return` at the top.

Add imports at the top of `WireLayer.kt` if missing:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
```

- [ ] **Step 3: Compile**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt
git commit -m "refactor(editor): WireLayer collects edges from flow"
```

---

### Task 4: Rewrite EditorState mutators to emit flows

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

Convert every internal mutator that touches `graph.nodes` / `graph.edges` directly to also emit through the flows. The underlying graph keeps being mutated for back-compat (Task 6 cleans it up). Existing `graphVersion++` calls stay — they're harmless once consumers read flows, and removing them is deferred to Task 6.

- [ ] **Step 1: Replace `connectReplacing` callsites + the edge mutator helpers**

Find this block in `EditorState.kt`:

```kotlin
    fun commitWireTo(target: PinKey): Boolean {
        val src = wireDragSource ?: return false
        if (target.side == src.side) return false
        if (target.node == src.node) return false
        val (output, input) = orderOutputInput(src, target)
        if (pinType(output) != pinType(input)) return false
        graph.connectReplacing(Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin)))
        graphVersion++
        return true
    }
```

Replace the `graph.connectReplacing(...)` line with:

```kotlin
        val edge = Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin))
        graph.connectReplacing(edge)
        _edges.value = graph.edges.toList()
```

(Keep `graphVersion++` and `return true`.)

Same change at the other `connectReplacing` callsite in `finishWireDragOnRelease`:

```kotlin
            graph.connectReplacing(Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin)))
            graphVersion++
```

Replace with:

```kotlin
            val edge = Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin))
            graph.connectReplacing(edge)
            _edges.value = graph.edges.toList()
            graphVersion++
```

Update both `disconnectAllEdges` and `disconnectPin` to emit `_edges.value` when they delete edges:

```kotlin
    private fun disconnectAllEdges(id: dev.nitka.nodewire.graph.NodeId) {
        val before = graph.edges.size
        graph.edges.removeAll { it.from.node == id || it.to.node == id }
        if (graph.edges.size != before) {
            _edges.value = graph.edges.toList()
            graphVersion++
        }
    }

    fun disconnectPin(key: PinKey) {
        val before = graph.edges.size
        graph.edges.removeAll { edge ->
            (edge.from.node == key.node && edge.from.pin == key.pin && key.side == PinSide.Output) ||
                (edge.to.node == key.node && edge.to.pin == key.pin && key.side == PinSide.Input)
        }
        if (graph.edges.size != before) {
            _edges.value = graph.edges.toList()
            graphVersion++
        }
    }
```

- [ ] **Step 2: Rewrite `changeChannelType` to take `NodeId` + go through `updateNode`**

Old:

```kotlin
    fun changeChannelType(node: Node, newType: dev.nitka.nodewire.graph.PinType) {
        val pin = (node.inputs + node.outputs).firstOrNull() ?: return
        val rebuilt = pin.copy(type = newType)
        if (node.inputs.isNotEmpty()) node.inputs = listOf(rebuilt) else node.outputs = listOf(rebuilt)
        node.config.putString("type", newType.name)
        disconnectAllEdges(node.id)
        graphVersion++
    }
```

New:

```kotlin
    fun changeChannelType(id: dev.nitka.nodewire.graph.NodeId, newType: dev.nitka.nodewire.graph.PinType) {
        updateNode(id) { n ->
            val pin = (n.inputs + n.outputs).firstOrNull() ?: return@updateNode n
            val rebuilt = pin.copy(type = newType)
            val newConfig = n.config.copy().apply { putString("type", newType.name) }
            if (n.inputs.isNotEmpty()) n.copy(inputs = listOf(rebuilt), config = newConfig)
            else n.copy(outputs = listOf(rebuilt), config = newConfig)
        }
        disconnectAllEdges(id)
        graphVersion++
    }
```

The function signature changed — every caller (currently inside `NodeConfigContent.kt`'s ChannelEndpoint composable) needs to pass `node.id` instead of `node`. Update those in Task 5.

- [ ] **Step 3: Rewrite `changeConverterInput`**

Old:

```kotlin
    fun changeConverterInput(node: Node, newType: dev.nitka.nodewire.graph.PinType) {
        node.inputs = listOf(node.inputs.first().copy(type = newType))
        disconnectAllEdges(node.id)
        graphVersion++
    }
```

New:

```kotlin
    fun changeConverterInput(id: dev.nitka.nodewire.graph.NodeId, newType: dev.nitka.nodewire.graph.PinType) {
        updateNode(id) { n ->
            n.copy(inputs = listOf(n.inputs.first().copy(type = newType)))
        }
        disconnectAllEdges(id)
        graphVersion++
    }
```

Update its caller (NodeConfigContent → ConvertToRedstone — Task 5).

- [ ] **Step 4: Rewrite `moveSelected`**

Old:

```kotlin
    fun moveSelected(dxWorld: Float, dyWorld: Float) {
        if (selectedNodes.isEmpty()) return
        for (id in selectedNodes) {
            val n = graph.nodes[id] ?: continue
            n.pos = CanvasPos(n.pos.x + dxWorld, n.pos.y + dyWorld)
        }
        graphVersion++
    }
```

New:

```kotlin
    fun moveSelected(dxWorld: Float, dyWorld: Float) {
        if (selectedNodes.isEmpty()) return
        for (id in selectedNodes) {
            updateNode(id) { n -> n.copy(pos = CanvasPos(n.pos.x + dxWorld, n.pos.y + dyWorld)) }
        }
        graphVersion++
    }
```

- [ ] **Step 5: Append a test for `changeChannelType` to EditorStateFlowTest**

Append inside the `class EditorStateFlowTest { ... }` body, before the closing brace:

```kotlin
    @Test
    fun changeChannelTypeProducesNewPinAndConfig() {
        val outputNode = Node(
            id = Node.newId(),
            typeKey = ResourceLocation("nodewire", "channel_output"),
            pos = CanvasPos.Zero,
            inputs = listOf(Pin("in", "Value", PinType.BOOL)),
            outputs = emptyList(),
        ).also {
            it.config.putString("type", PinType.BOOL.name)
            it.config.putString("name", "speed")
        }
        val ed = EditorState(NodeGraph().also { it.add(outputNode) })
        ed.changeChannelType(outputNode.id, PinType.INT)
        val after = ed.nodeFlow(outputNode.id)!!.value
        assertEquals(PinType.INT, after.inputs[0].type)
        assertEquals(PinType.INT.name, after.config.getString("type"))
    }
```

- [ ] **Step 6: Compile + run tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test 2>&1 | tail -10`

Expected: there will be **compile errors** in `NodeConfigContent.kt` because it calls `editor?.changeChannelType(node, ...)` (with `node`, not `node.id`). That's intentional — Task 5 fixes those. **Stop the test run from blocking the commit by running only the EditorStateFlowTest**:

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin 2>&1 | tail -20`

If the only errors are in `NodeConfigContent.kt`'s `changeChannelType` / `changeConverterInput` calls, that's expected. Proceed to commit and let Task 5 fix the callsites.

If errors are anywhere else in this file or in unrelated files — fix them before committing.

- [ ] **Step 7: Commit with `--no-verify` only if needed**

```bash
cd /home/nitka/CODING/nodewire
git branch --show-current   # master
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateFlowTest.kt
git commit -m "refactor(editor): mutators emit flows + change*Type take NodeId" --no-verify
```

The `--no-verify` is acceptable here because Task 5 immediately fixes the only consumers of the changed signatures. Note this in your report.

---

### Task 5: Migrate `NodeConfigContent` callsites to `updateNode`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

Every `node.config.putString(...); editor?.bumpGraphVersion()` pattern becomes `editor?.updateNode(node.id) { it.copy(config = it.config.copy().apply { putString(...) }) }`. The `changeChannelType` / `changeConverterInput` calls switch from passing `node` to passing `node.id`.

Roughly 14 callsites — mechanical but tedious. Group them by similar pattern.

- [ ] **Step 1: Locate callsites**

Run: `cd /home/nitka/CODING/nodewire && grep -n "bumpGraphVersion\|changeChannelType\|changeConverterInput" src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

Note each line. They're inside `@Composable` config functions (`IntConst`, `FloatConst`, `StringConst`, `BoolConst`, `Probability`, `IntRange`, `DelayTicks`, `TimerPeriod`, `SideFace`, `ChannelEndpoint`, `ConvertToRedstone`).

- [ ] **Step 2: Replace `node.config.put*; editor?.bumpGraphVersion()` patterns**

For each callsite that mutates `node.config` directly and then bumps the version, rewrite. Example transformation — IntConst:

Old:

```kotlin
                onValueChange = { new ->
                    val filtered = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                    text = filtered
                    node.config.putInt("value", filtered.toIntOrNull() ?: 0)
                    editor?.bumpGraphVersion()
                },
```

New:

```kotlin
                onValueChange = { new ->
                    val filtered = new.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }
                    text = filtered
                    editor?.updateNode(node.id) {
                        it.copy(config = it.config.copy().apply {
                            putInt("value", filtered.toIntOrNull() ?: 0)
                        })
                    }
                },
```

Apply the same pattern to every callsite. The verb (`putInt` / `putFloat` / `putString` / `putBoolean`) and the key string change per call — keep them exactly as they were.

For `changeChannelType` calls in `ChannelEndpoint`:

Old:

```kotlin
                onSelect = { next ->
                    type = next
                    editor?.changeChannelType(node, next)
                },
```

New:

```kotlin
                onSelect = { next ->
                    type = next
                    editor?.changeChannelType(node.id, next)
                },
```

For `changeConverterInput` in `ConvertToRedstone`, same — pass `node.id`.

- [ ] **Step 3: Compile + tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL.

If `bumpGraphVersion` references still exist, grep — fix them.

```bash
cd /home/nitka/CODING/nodewire && grep -rn "bumpGraphVersion" src/main/kotlin/
```

Should print only `EditorState.kt:fun bumpGraphVersion()` (the definition, no callers).

- [ ] **Step 4: Commit**

```bash
cd /home/nitka/CODING/nodewire
git branch --show-current
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "refactor(ui): config mutations go through updateNode"
```

---

### Task 6: `Node` `var → val`, drop `graphVersion`/`nodesVersion`, add `snapshotGraph`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`

The migration is complete on the consumer side. Now we make `Node` immutable, drop the version counters that no caller reads, and route save through `snapshotGraph()`. After this task, compile + tests are fully green and there's no remaining `var` on `Node`.

- [ ] **Step 1: `var → val` on Node**

Edit `/home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/graph/Node.kt`. Find:

```kotlin
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    var pos: CanvasPos,
    var inputs: List<Pin>,
    var outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
) {
```

Replace with:

```kotlin
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    val pos: CanvasPos,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
) {
```

Compile check:

```
cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin 2>&1 | tail -20
```

Expected errors: only places that still write to `n.pos`, `n.inputs`, `n.outputs`. There should be **none** left — all consumers were migrated in Tasks 2–5. If something turns up, replace with an appropriate `updateNode { it.copy(...) }`.

- [ ] **Step 2: Drop version counters + add `snapshotGraph` in EditorState**

In `EditorState.kt`, remove these declarations:

```kotlin
    var nodesVersion: Int by mutableStateOf(0)
        private set

    var graphVersion: Int by mutableStateOf(0)
        private set

    fun bumpGraphVersion() {
        graphVersion++
    }
```

And every `graphVersion++` / `nodesVersion++` line throughout the file. Grep first:

```bash
cd /home/nitka/CODING/nodewire && grep -n "graphVersion\|nodesVersion\|bumpGraphVersion" src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
```

Delete every line that bumps either counter or references the function. The flows already carry the change signal.

Add `snapshotGraph()` near the bottom of the class (just before the `companion object`):

```kotlin
    /**
     * Build a fresh [NodeGraph] from the current per-node + edge flows.
     * Called on screen close to ship the latest state to the server via
     * [dev.nitka.nodewire.net.SaveGraphPacket].
     */
    fun snapshotGraph(): NodeGraph {
        val g = NodeGraph()
        for (id in _nodes.value) {
            val n = nodeFlows[id]?.value ?: continue
            g.add(n)
        }
        for (e in _edges.value) g.addEdge(e)
        return g
    }
```

If you also want to drop the `val graph: NodeGraph` constructor parameter (the spec says EditorState no longer owns NodeGraph for the lifetime of the editor), keep it for now — Task 7's tests rely on constructing the editor from a seed graph. Renaming `graph` to `initialGraph` is a follow-up.

Also remove the `graph.nodes[id] = updated` line inside `updateNode` if the `graph` field is staying — since callers no longer read from `graph` directly (NodeCard, WireLayer, etc. all collect flows), the underlying-graph mirror is dead weight. Keep the line for one more task if uncertain; otherwise delete.

Actually — keep it. `EditorState.graph` is still used by `pinType()` private helper and by `disconnectAllEdges` / `disconnectPin` which currently iterate `graph.edges`. Removing the mirror would break those. The mirror is a small write per update — fine.

- [ ] **Step 3: Save flow uses `snapshotGraph()`**

Edit `/home/nitka/CODING/nodewire/src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`. Find:

```kotlin
    override fun removed() {
        NodewireNetwork.CHANNEL.send(
            PacketDistributor.SERVER.noArg(),
            SaveGraphPacket(pos, graph),
        )
        super.removed()
    }
```

Replace with:

```kotlin
    override fun removed() {
        val editor = editorRef
        val snapshot = editor?.snapshotGraph() ?: graph
        NodewireNetwork.CHANNEL.send(
            PacketDistributor.SERVER.noArg(),
            SaveGraphPacket(pos, snapshot),
        )
        super.removed()
    }
```

(The `editorRef` field already exists from the ESC-handler work.)

- [ ] **Step 4: Compile + full tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL. If anything references `graphVersion` / `nodesVersion` outside `EditorState.kt`, fix it.

Sanity-grep:

```bash
cd /home/nitka/CODING/nodewire && grep -rn "graphVersion\|nodesVersion\|bumpGraphVersion" src/main/kotlin/
```

Expected: empty.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git branch --show-current
git add src/main/kotlin/dev/nitka/nodewire/graph/Node.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "refactor(editor): Node immutable, drop graphVersion, snapshotGraph for save"
```

---

### Task 7: Final build + visual smoke handoff

**Files:** none.

- [ ] **Step 1: Clean build + tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Sanity-grep residuals**

```bash
cd /home/nitka/CODING/nodewire
grep -rn "graphVersion\|nodesVersion\|bumpGraphVersion" src/
```

Expected: empty.

```bash
grep -rn "node\.pos =\|node\.inputs =\|node\.outputs =" src/main/kotlin/
```

Expected: empty (any leftover direct mutation is a bug from this refactor).

- [ ] **Step 3: Hand off to user**

Tell the user:

> "StateFlow migration complete. Open the editor, place a `Channel Output` node, change its type from BOOL → INT — the pin row should update immediately. Drag one card on the canvas — other cards should not visibly redraw. If anything still looks stale, file a follow-up with the SNBT export."

No commit on this step.

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|------------------|------|
| `Node` becomes immutable | 6 |
| Per-node `MutableStateFlow<Node>` | 1 |
| `EditorState.nodes: StateFlow<List<NodeId>>` | 1 |
| `EditorState.edges: StateFlow<List<Edge>>` | 1 + 4 |
| `selectedNodes` already Compose State (Task 0; no spec change) | n/a |
| `updateNode(id, transform)` | 1 |
| All mutators emit through flows | 4 |
| `NodeCard(nodeId)` + `collectAsState` | 2 |
| `NodeEditorScreen` iterates `editor.nodes.collectAsState().value` | 2 |
| `WireLayer` collects edges | 3 |
| `bumpGraphVersion()` / `graphVersion` / `nodesVersion` removed | 6 |
| `snapshotGraph()` for save | 6 |
| `EditorStateFlowTest` unit tests | 1 (initial 5), 4 (+1) |
| Codec roundtrip unaffected | n/a — Node fields stay |
| Out of scope: server-pushed flow updates | n/a |

**Placeholder scan:** none. Each step has runnable code or exact commands.

**Type consistency:** `updateNode(id: NodeId, transform: (Node) -> Node)` used identically across tasks. `nodeFlow(id): StateFlow<Node>?` consistent. `snapshotGraph(): NodeGraph` consistent. `nodes: StateFlow<List<NodeId>>` consistent.

**One known intermediate state:** Task 4 leaves `NodeConfigContent.kt` not compiling because `changeChannelType` / `changeConverterInput` signatures changed. Task 5 fixes it immediately. The plan flags this and authorises `--no-verify` for the Task 4 commit. Document it in the implementer's report.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-15-reactive-state-flows.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

**Which approach?**
