# Node Groups / Composition — Design

**Date:** 2026-05-17
**Status:** Approved, awaiting implementation plan

## Goal

Let players group parts of a graph into reusable, nestable, collapsible
units — Blender Frames meet Unreal Blueprint reuse. Groups can be:

- **Inline (anonymous):** a visual container scoped to the host graph,
  used to organise and collapse a region.
- **Linked (named, file-backed):** persisted to a local file under
  `<gamedir>/nodewire-groups/<name>.snbt`, instantiable across graphs
  with live edit-master semantics: any edit inside any instance flows
  back to the file and is mirrored into every other open instance.

Groups can nest arbitrarily. They can be collapsed so they occupy a
single tile with auto-derived proxy pins, or expanded into a frame
around their members.

## Non-goals

- No cross-client live sync between separate clients' open editors.
  Sync within one client only; other clients pick up file state on
  next open.
- No template rename / refactor tooling. "Save as" with a new name is
  the recommended path.
- No conflict resolution for two editors on the same client editing
  two instances of the same template concurrently — last write wins
  through the template store's single source of truth.

## Architecture

Groups are **pure visual metadata layered over a flat NodeGraph**.
Evaluator sees a flat graph and ignores groups entirely. This is the
crucial decision driving the rest of the design.

Consequences:

- Wires between outside nodes and nodes inside a group are direct
  `PinRef(internalNodeId, pin) ↔ PinRef(externalNodeId, pin)` edges.
  No proxy / marker nodes (no `GroupInput`/`GroupOutput`).
- When a group is collapsed, proxy pins are *rendered* (not stored)
  for every internal pin that has a cross-boundary edge. Drag a wire
  to a collapsed group's proxy pin and the wire still terminates at
  the underlying internal node.
- Stateful nodes (e.g. Timer) inside a template instance are real
  nodes in the flat graph with their own UUIDs, so each instance gets
  its own state automatically.

## Data model

```kotlin
@JvmInline value class GroupId(val value: UUID)
@JvmInline value class TemplateNodeId(val value: UUID)

data class Group(
    val id: GroupId,
    val name: String,
    val members: List<MemberRef>,
    val templateFile: String?,                    // null → inline anonymous
    val templateIdMap: Map<TemplateNodeId, NodeId>?,  // null → inline
    val collapsed: Boolean,
    val pos: CanvasPos,                           // anchor; tile position when collapsed, bbox top-left when expanded
    val collapsedSize: Pair<Int, Int>?,           // remembered tile size
    val pinLabelOverrides: Map<PinKey, String> = emptyMap(), // optional rename of proxy pin labels
)

sealed interface MemberRef {
    @JvmInline value class Node(val id: NodeId) : MemberRef
    @JvmInline value class Sub(val id: GroupId) : MemberRef
}
```

`NodeGraph` gains a `groups: MutableList<Group>`. `NodeGraph.CODEC`
is extended to round-trip the list. Server treats `Group` records as
opaque, evaluator ignores them.

### Belongs-to relation

A node / sub-group belongs to at most one immediate parent group,
derived by scanning `groups` for membership. The editor enforces this
invariant when groups are created or modified.

## Template store and live sync

`GroupTemplateStore` (client-only, session-scoped):

- Keyed by `templateFile` name.
- Each entry holds a `MutableStateFlow<GroupTemplate>` where
  `GroupTemplate = (nodes, edges, groups)` keyed by `TemplateNodeId`.
- Hydrated from disk on first reference. Written back with 300 ms
  debounce on every change.
- All open editor sessions on this client subscribe.

### Edit → propagate flow

1. User edits a node inside a linked instance. The editor stays in
   instance-local IDs while editing.
2. On commit (any `mutateGraph` inside group membership), the editor
   translates instance IDs → `TemplateNodeId` through the instance's
   `templateIdMap` and applies the diff to the store's flow.
3. The flow ticks → every subscribed instance (including the one that
   originated the edit) re-resolves:
   - **Stable IDs:** template IDs already in `templateIdMap` keep
     their existing runtime `NodeId`. External wires terminating on
     those internals are preserved.
   - **New template nodes:** generate fresh runtime UUIDs and extend
     the idMap.
   - **Removed template nodes:** drop from the graph; any external
     edge touching them is dropped with a toast `Edge dropped: <pin>`.
   - **Internal edges:** rebuilt from template wholesale.

### Resolve order — initial instantiation

Inserting a template at world position `(x, y)`:

1. Read or hydrate the template.
2. For each template node, allocate a fresh `NodeId`; record in
   `templateIdMap`.
3. Translate internal edges via the map; add to host graph.
4. Recursively resolve nested groups inside the template the same way
   (allocating new `GroupId`s, etc.).
5. Append a new `Group` record with the populated `templateIdMap`.

## UI: rendering

### Expanded

- A semi-transparent frame is drawn behind member nodes — Blender-style.
- Header strip on top with: group name, file-link badge (if linked),
  collapse icon, more-actions overflow.
- Dragging the header moves every member node (and member group) by
  the same delta.
- Nodes inside render normally; wires connect directly.

### Collapsed

- One tile at `Group.pos` with `collapsedSize` (default sized from
  proxy pin count).
- Proxy pin rows derived at render time:
  - For each edge `(u, v)` where exactly one endpoint's node is inside
    the group, the in-group pin appears on the corresponding side of
    the tile (input pin → left, output pin → right).
  - Label = `pinLabelOverrides[key] ?: "${nodeName}.${pinLabel}"`.
- Wires from outside terminate visually at the proxy but, in the
  model, still reference the inner node — rendering interpolates from
  the proxy position to the underlying node's logical position. Edge
  data is unchanged by collapse / expand.
- Right-click on tile → standard group menu (Save as template, Unlink,
  Ungroup, Edit pin labels, Delete group keeping nodes / dropping
  nodes).

### Nested groups while collapsed

When a parent is collapsed, all nested groups are visually subsumed
into the parent tile and their own collapsed/expanded state is
preserved (re-exposed when the parent re-expands).

## UI: interactions

- **Group selection:** select N nodes, `Ctrl+G` or context-menu
  "Group". Asks for a name; cancelling yields an inline anonymous
  group with placeholder name (`Group <n>`).
- **Save as template:** group context-menu → "Save as template…" →
  asks for a filename. Inline group converts to linked: a file is
  created, `templateIdMap` is populated by reversing the current
  instance's IDs into newly-allocated `TemplateNodeId`s.
- **Insert from template:** canvas right-click → "Insert group" → list
  of saved templates.
- **Drag in / out:** dragging a free node into a group's bbox adds it
  to `members`; dragging out removes it.
- **Unlink:** detach instance from its file; the group becomes inline.
  Template file is untouched.
- **Ungroup:** dissolve the group, members survive at their current
  positions, edges untouched.

## Server transmission

- The full graph (nodes + edges + groups) is shipped to the server via
  the existing `SaveGraphPacket`.
- Server stores groups as opaque metadata in the BE.
- Server-side `GraphEvaluator` ignores groups.
- When another client opens the BE, it receives the same `groups`
  list. Linked-instance `templateIdMap`s let it re-attach to local
  files; if the local file is missing, the group becomes read-only
  (members and their edges are already present in the host graph;
  only edit-propagation back to the file is blocked) and a warning
  badge is shown.

## Edge cases

- **Missing template file at open:** instance keeps its last
  resolved nodes/edges in the host graph, badge shows "template
  missing".
- **Cyclic templates:** detected at resolve time
  (`A` references `B` which references `A`) — refuses to insert and
  toasts an error.
- **Member node deleted via normal Delete:** `EditorState.removeNode`
  invariant — also strip the deleted id from every group's `members`.
  Empty inline groups are auto-removed; linked groups stay (the
  template still defines what should be there; next file-driven
  resolve will re-instantiate the missing nodes).
- **Renaming a template file from outside MC:** instances do not
  auto-discover. Treated as "missing template" until user does
  Save-as / Insert again.
- **Two open editors on this client editing instances of the same
  template:** safe — both share the same `MutableStateFlow`. Last
  write to the flow wins; the other editor recomposes.

## Decomposition

Single plan covers all of it — the pieces are tightly coupled
(data model + codec + template store + UI all share `Group` shape).

## Testing

Unit:

- `GroupCodecTest` — round-trip `Group` and `NodeGraph` containing
  groups through NBT.
- `GroupTemplateResolverTest` — instantiate, apply template change
  with add / remove / modify, verify external-edge preservation /
  drop semantics.
- `GroupNestingTest` — nested groups, collapse outer hides inner,
  expand restores inner's state.
- `GroupBboxTest` — auto-compute expanded bbox from member positions.
- `GroupMembershipTest` — drag in / out updates `members`; single
  parent invariant.
- `GroupCycleDetectionTest` — refuses cyclic template insertion.

Integration (manual, no harness yet):

- Insert template twice in same graph; edit inside one; the other
  updates live. Per-instance Timer state stays isolated.
- Save graph with groups, reopen, groups + collapsed state preserved.
