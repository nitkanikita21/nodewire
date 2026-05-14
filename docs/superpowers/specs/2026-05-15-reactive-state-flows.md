# Reactive editor state via StateFlow

## Context

Today the editor's UI reactivity is built on a single coarse counter:
`EditorState.graphVersion: Int by mutableStateOf(0)`. Every mutation —
move, add/remove, pin-type change, channel-config edit — bumps it; every
composable that wants to react reads `editor.graphVersion` at the top to
force a recomposition of itself on any change.

This has two real downsides:

1. **Stale pin display.** When `EditorState.changeChannelType` rebuilds a
   node's `inputs` / `outputs` (`Node.inputs/outputs` are `var List<Pin>`
   — invisible to Compose), the pin row composable may keep a stale
   layout because it was hidden behind a `remember(node) { … }` keyed on
   the stable Node reference. The version bump *should* force re-read,
   but in practice the pin row only redraws after another perturbation.
2. **Coarse invalidation.** Dragging one card recomposes every other
   card on the canvas. Not catastrophic at the current node count, but
   wasteful and confuses surrounding state like focus/hover.

Move to **`StateFlow<Node>` per node + `StateFlow<List<NodeId>>` for the
graph membership + `StateFlow<List<Edge>>` for edges**. Composables read
via `collectAsState()`. Drop the `graphVersion` / `nodesVersion`
counters.

## Goals

- Each node owns a `MutableStateFlow<Node>` on the editor side. Card
  composables read a Node snapshot from that flow.
- Mutations go through editor methods that emit a new `Node` instance
  (via `Node.copy(...)`), so `StateFlow` distinct-by-equality semantics
  correctly trigger downstream collectors.
- `EditorState.nodesFlow: StateFlow<List<NodeId>>` for membership.
  Adding / removing a node updates this flow.
- `EditorState.edgesFlow: StateFlow<List<Edge>>` for edges. The
  `WireLayer` collects from it.
- Selection (`selectedNodes`) becomes `MutableStateFlow<Set<NodeId>>` —
  same `.collectAsState()` pattern.
- `Node` becomes immutable (no `var` mutable properties). `pos`,
  `inputs`, `outputs`, `config` all become `val`. Mutations produce a
  new instance via `.copy(...)`. Config (CompoundTag, mutable inside) is
  copied with `.copy()` when mutated.
- No more `graphVersion` / `nodesVersion` / `bumpGraphVersion()`.
- `LocalEditorState` still exists as the entry point composables use
  to reach the editor. Per-node flows are looked up via
  `editor.nodeFlow(id)`.

## Non-goals

- Cross-process Flow plumbing (server → client). Save / load still uses
  packets + `LogicBlockEntity` updates. The block-entity update path
  remains: server pushes new `Node` snapshots into the per-node flows
  on the client when a `ClientboundBlockEntityDataPacket` lands. Wire
  that as a follow-up if the spec touches it; otherwise stays as is.
- Replacing `selectionDragStart`/`selectionDragCurrent` / `wireDrag*`
  with flows. They're already Compose State; no benefit.
- Background coroutine work for live evaluation (`StatefulGraphEvaluator`
  ticking). Already uses `LaunchedEffect` + `delay`; this spec doesn't
  touch it.
- Wrapping every CompositionLocal as a Flow. The reactivity boundary is
  per-node state and graph membership only.

## Architectural changes

### Node — immutable

Today:

```kotlin
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    var pos: CanvasPos,
    var inputs: List<Pin>,
    var outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
)
```

After:

```kotlin
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    val pos: CanvasPos,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
)
```

All four `var → val`. Callers that mutate must `.copy(...)`. Codec
unaffected (the codec uses these as getters in `RecordCodecBuilder`).

### EditorState — flows

Replace today's:

```kotlin
class EditorState(val graph: NodeGraph, val pos: BlockPos) {
    var nodesVersion: Int by mutableStateOf(0)
    var graphVersion: Int by mutableStateOf(0)
    ...
}
```

with (sketch):

```kotlin
class EditorState(
    initialGraph: NodeGraph,
    val pos: BlockPos = BlockPos.ZERO,
) {
    // Per-node flows; the canonical state lives here, not in the
    // underlying NodeGraph. The NodeGraph instance is mutated only at
    // save / serialization time to mirror these flows back to the
    // codec-encodable form.
    private val nodeFlows: MutableMap<NodeId, MutableStateFlow<Node>> =
        initialGraph.nodes.mapValues { MutableStateFlow(it.value) }.toMutableMap()

    private val _nodes = MutableStateFlow(initialGraph.nodes.keys.toList())
    val nodes: StateFlow<List<NodeId>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow(initialGraph.edges.toList())
    val edges: StateFlow<List<Edge>> = _edges.asStateFlow()

    private val _selected = MutableStateFlow<Set<NodeId>>(emptySet())
    val selectedNodes: StateFlow<Set<NodeId>> = _selected.asStateFlow()

    fun nodeFlow(id: NodeId): StateFlow<Node>? = nodeFlows[id]?.asStateFlow()

    /** Apply a transform to one node, emit the new value. No-ops if id is gone. */
    fun updateNode(id: NodeId, transform: (Node) -> Node) {
        val flow = nodeFlows[id] ?: return
        flow.value = transform(flow.value)
    }

    fun addNode(node: Node) {
        nodeFlows[node.id] = MutableStateFlow(node)
        _nodes.value = _nodes.value + node.id
    }

    fun removeNode(id: NodeId) {
        if (nodeFlows.remove(id) == null) return
        _nodes.value = _nodes.value - id
        _edges.value = _edges.value.filter { it.from.node != id && it.to.node != id }
    }

    // edge mutators similarly emit new lists into _edges
}
```

### NodeGraph used at save time only

`EditorState` no longer "owns" `NodeGraph.nodes`/`edges` for the lifetime
of the editor. On save (`NodeEditorScreen.removed()`), build a fresh
`NodeGraph` by snapshotting every flow:

```kotlin
fun snapshotGraph(): NodeGraph {
    val g = NodeGraph()
    for (id in nodes.value) {
        val n = nodeFlows[id]?.value ?: continue
        g.add(n)
    }
    g.edges.addAll(edges.value)
    return g
}
```

`SaveGraphPacket` then ships `editor.snapshotGraph()`.

### Composables read via `collectAsState`

`NodeCard`:

```kotlin
@Composable
fun NodeCard(nodeId: NodeId, modifier: Modifier = Modifier) {
    val editor = LocalEditorState.current ?: return
    val flow = editor.nodeFlow(nodeId) ?: return
    val node by flow.collectAsState()
    val selected by editor.selectedNodes.map { nodeId in it }.collectAsState(initial = false)
    // ... draw using `node`, `selected`
}
```

Note the change of signature: `NodeCard(node: Node)` → `NodeCard(nodeId: NodeId)`. The card subscribes to its own flow rather than receiving a stale reference. The screen's `for (id in nodes.collectAsState().value) { NodeCard(id) }` pattern naturally re-runs when membership changes.

`WireLayer`:

```kotlin
val edges by editor.edges.collectAsState()
// renders edges and pin positions; pin positions still come from PinPositions map
```

### Mutator rewrite — single example

`changeChannelType` today:

```kotlin
fun changeChannelType(node: Node, newType: PinType) {
    val pin = (node.inputs + node.outputs).firstOrNull() ?: return
    val rebuilt = pin.copy(type = newType)
    if (node.inputs.isNotEmpty()) node.inputs = listOf(rebuilt) else node.outputs = listOf(rebuilt)
    node.config.putString("type", newType.name)
    disconnectAllEdges(node.id)
    graphVersion++
}
```

After:

```kotlin
fun changeChannelType(id: NodeId, newType: PinType) {
    updateNode(id) { n ->
        val pin = (n.inputs + n.outputs).firstOrNull() ?: return@updateNode n
        val rebuilt = pin.copy(type = newType)
        val newConfig = n.config.copy().apply { putString("type", newType.name) }
        if (n.inputs.isNotEmpty()) n.copy(inputs = listOf(rebuilt), config = newConfig)
        else n.copy(outputs = listOf(rebuilt), config = newConfig)
    }
    disconnectAllEdges(id)
}
```

Same shape for `changeConverterInput`, `moveSelected`, `duplicateNode`,
`disconnectAllEdges`. All emit through flows; no version counter.

## Coroutine + threading

- Editor is single-threaded on the MC client thread. `MutableStateFlow.value = …` is thread-safe but ordering on a single thread is trivially correct.
- `collectAsState()` lives in compose-runtime — already on the classpath. No new deps.
- No new coroutine scope creation. The Compose recomposer's scope (`Recomposer` inside `NwUiOwner`) collects state changes via the snapshot system; `collectAsState` plugs into the same system.

## Files touched

**Modify:**
- `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` — `var` → `val`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` — full rewrite of the state-holding parts; preserve the API surface (mutator method names) where possible so callers don't churn.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` — accept `nodeId: NodeId` and `collectAsState`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` — iterate `editor.nodes.collectAsState().value` instead of a remembered list; snapshot for save.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` — collect `editor.edges`.
- `src/main/kotlin/dev/nitka/nodewire/client/screen/SelectionRect.kt` — read selection-drag start/current; already Compose State so no change beyond surface adjustments.
- Any other call sites that read `node.pos` / `node.inputs` / etc. (the grep will turn them up) — they receive a stale snapshot through Compose; switch to flow.

**Search-replace targets:**
- Every assignment `node.inputs = ...`, `node.outputs = ...`, `node.pos = ...` is now a compile error; replace with `editor.updateNode(node.id) { it.copy(...) }`.
- Every read of `editor.graphVersion` / `editor.nodesVersion` is dropped — collectors give per-flow reactivity.
- `editor.bumpGraphVersion()` calls are dropped.

## Test plan

- Existing `UiNodeStyleResetTest` and codec round-trip tests must still pass.
- New unit test: `EditorStateFlowTest` covering
  - `addNode` emits new `nodes` list (`StateFlow.value` reflects).
  - `updateNode` emits a new `Node` from the per-node flow.
  - `removeNode` deletes the per-node flow and prunes edges that touch the removed id.
  - `changeChannelType` produces a Node whose `inputs[0].type == newType` AND whose config has the new "type" string, in one transactional update.
- Visual smoke: place a ChannelInput node, edit its type from BOOL → INT in the editor — pin row redraws immediately, edges that no longer match disconnect, the rest of the canvas does not flicker.

## Out of scope

- A future ticket may unify "live evaluator output" (`LocalEvalResult`) into the same Flow story so pin-value labels update reactively per-pin instead of via a snapshot per tick.
- Bidirectional flow between server BE and client editor (server-pushed graph diffs) — currently uses NBT round-trip; revisit when we want collaborative editing.

## Rollout note

Existing saves continue to load because `Node` shape on disk is
unchanged. The change is purely runtime / in-memory — codec serialization
sees the same record fields.
