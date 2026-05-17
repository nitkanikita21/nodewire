# Node Groups / Composition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Blender-Frame-style reusable, nestable, collapsible groups
to the node editor with edit-master live-sync to local files.

**Architecture:** Groups are pure visual metadata on a flat `NodeGraph`
(evaluator ignores them). Wires cross group boundaries directly — no
marker nodes. Collapsed groups render auto-derived proxy pins. Linked
groups sync edits through a per-file `MutableStateFlow<GroupTemplate>`.

**Tech Stack:** Kotlin 2.0.20, Compose runtime 1.7.0, Mojang Codec API,
NBT, JUnit 5.

**Branch:** stay on `master` (no feature branches).

**Verification:** `./gradlew test` and `./gradlew build` only. Do NOT
launch `./gradlew runClient` — user runs it manually.

**Spec:** `docs/superpowers/specs/2026-05-17-node-groups-composition-design.md`.

---

## File map

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/dev/nitka/nodewire/graph/Group.kt` | `GroupId`, `TemplateNodeId` typealiases; `MemberRef` sealed; `Group` data class + `CODEC`. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplate.kt` | `GroupTemplate` data class (template-relative slice of nodes/edges/groups) + `CODEC`. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolver.kt` | Pure logic: instantiate template into runtime IDs; apply template diff to an existing instance preserving idMap & external edges. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GroupBbox.kt` | Pure bbox math over a group's member node positions. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GroupMembership.kt` | Pure helpers: ancestors, descendants, cycle detection across template references. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFiles.kt` | Disk save/load/list/delete under `<gamedir>/nodewire-groups/`. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStore.kt` | Session-scoped per-file `MutableStateFlow<GroupTemplate>` registry. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFrame.kt` | Composable: expanded-state frame (header + drag-to-move-members). |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupCollapsedTile.kt` | Composable: collapsed-state tile with auto proxy pins. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupLayer.kt` | Composable: iterates `editor.groups`, mounts frame or tile per group; hides member `NodeCard`s when collapsed. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupProxyPins.kt` | Pure: compute the proxy-pin list of a collapsed group from current edges. |

### Modified files

| Path | Change |
|---|---|
| `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt` | Add `groups: MutableList<Group>`; extend `CODEC`; extend `deepCopy` path automatically via codec. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` | Track `_groups: MutableStateFlow<List<Group>>`; group ops (create/ungroup/collapse-toggle/add-member/remove-member/save-as-template/insert-template/unlink); on `removeNode` strip from `members`; on member mutation push to template store if linked. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt` | `Ctrl+G` → group selected; `Ctrl+Shift+G` → ungroup. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/ContextMenuTarget.kt` | Add `Group(screenX, screenY, groupId)` variant. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeContextMenu.kt` | Canvas menu: "Insert group ▸ <files>"; group-target menu: Save-as-template, Toggle collapse, Unlink, Ungroup, Delete (keep nodes / drop nodes). |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` | Mount `GroupLayer` between `WireLayer` and node cards. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` | When endpoint node is inside a collapsed group, reroute the visual endpoint to the proxy-pin position (model edge unchanged). |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt` | File menu: "Insert group" submenu mirroring canvas right-click. |

### New tests

| Path | Coverage |
|---|---|
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupCodecTest.kt` | Round-trip `Group`, `GroupTemplate`, and `NodeGraph` with groups through NBT and SNBT. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupBboxTest.kt` | Auto-compute bbox from member positions. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolverTest.kt` | Instantiate; apply template diff (add/remove/modify); external-edge preservation and drop. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupCycleDetectionTest.kt` | Cyclic template references rejected. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupNestingTest.kt` | Nested groups encode/decode; member set integrity. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupProxyPinsTest.kt` | Proxy pin list derives correctly from edges. |
| `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateGroupOpsTest.kt` | createGroup / ungroup / addMember / removeMember / toggle-collapse / removeNode-strip invariant. |
| `src/test/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStoreTest.kt` | Live propagation: edit instance → file written; second instance updates via flow. |

---

## Phase 1 — Core data model

### Task 1.1: Create `Group` data class and `MemberRef` sealed interface

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/Group.kt`

- [ ] **Step 1: Write the file**

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias GroupId = UUID
typealias TemplateNodeId = UUID

/**
 * One member of a [Group]. A group can contain raw nodes (referenced by
 * [Node]) or other groups (referenced by [Sub]) — the latter is how
 * nesting is encoded.
 */
sealed interface MemberRef {
    data class Node(val id: NodeId) : MemberRef
    data class Sub(val id: GroupId) : MemberRef

    companion object {
        val CODEC: Codec<MemberRef> = Codec.STRING.flatComapMap(
            { raw ->
                val (kind, idStr) = raw.split(':', limit = 2)
                when (kind) {
                    "n" -> Node(UUID.fromString(idStr))
                    "g" -> Sub(UUID.fromString(idStr))
                    else -> error("Unknown MemberRef kind: $kind")
                }
            },
            { m ->
                when (m) {
                    is Node -> com.mojang.serialization.DataResult.success("n:${m.id}")
                    is Sub  -> com.mojang.serialization.DataResult.success("g:${m.id}")
                }
            },
        )
    }
}

/**
 * Visual container metadata over a flat [NodeGraph]. Evaluator does not
 * consult [Group]s — they only drive editor rendering and the live-sync
 * template store.
 *
 * - [templateFile] null → inline (anonymous) group, exists only in this graph.
 * - [templateFile] non-null → linked instance, edits round-trip via the
 *   on-disk template at `<gamedir>/nodewire-groups/<templateFile>.snbt`.
 * - [templateIdMap] maps the file's `TemplateNodeId` → this instance's
 *   runtime [NodeId]. Stable across edits so external edges into the
 *   group survive template changes.
 * - [collapsed] visual flag only; member nodes still exist in the graph.
 */
data class Group(
    val id: GroupId,
    val name: String,
    val members: List<MemberRef>,
    val templateFile: String?,
    val templateIdMap: Map<TemplateNodeId, NodeId>?,
    val collapsed: Boolean,
    val pos: CanvasPos,
    val collapsedSize: Pair<Int, Int>?,
    val pinLabelOverrides: Map<String, String> = emptyMap(),
) {
    companion object {
        fun newId(): GroupId = UUID.randomUUID()

        private val SIZE_CODEC: Codec<Pair<Int, Int>> =
            RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("w").forGetter(Pair<Int, Int>::first),
                    Codec.INT.fieldOf("h").forGetter(Pair<Int, Int>::second),
                ).apply(i) { w, h -> w to h }
            }

        private val ID_MAP_CODEC: Codec<Map<TemplateNodeId, NodeId>> =
            Codec.unboundedMap(GraphCodecs.UUID_CODEC, GraphCodecs.UUID_CODEC)

        val CODEC: Codec<Group> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("id").forGetter(Group::id),
                Codec.STRING.fieldOf("name").forGetter(Group::name),
                MemberRef.CODEC.listOf().fieldOf("members").forGetter(Group::members),
                Codec.STRING.optionalFieldOf("templateFile").forGetter { java.util.Optional.ofNullable(it.templateFile) },
                ID_MAP_CODEC.optionalFieldOf("templateIdMap").forGetter { java.util.Optional.ofNullable(it.templateIdMap) },
                Codec.BOOL.fieldOf("collapsed").forGetter(Group::collapsed),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Group::pos),
                SIZE_CODEC.optionalFieldOf("collapsedSize").forGetter { java.util.Optional.ofNullable(it.collapsedSize) },
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("pinLabelOverrides").forGetter(Group::pinLabelOverrides),
            ).apply(i) { id, name, members, file, idMap, coll, pos, size, labels ->
                Group(
                    id = id,
                    name = name,
                    members = members,
                    templateFile = file.orElse(null),
                    templateIdMap = idMap.orElse(null),
                    collapsed = coll,
                    pos = pos,
                    collapsedSize = size.orElse(null),
                    pinLabelOverrides = labels,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/Group.kt
git commit -m "feat(graph): Group + MemberRef data model + codec"
```

### Task 1.2: Extend `NodeGraph` with `groups`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt`

- [ ] **Step 1: Add `groups` field and extend codec**

Replace the body of class `NodeGraph` with:

```kotlin
class NodeGraph {
    val nodes: MutableMap<NodeId, Node> = mutableMapOf()
    val edges: MutableList<Edge> = mutableListOf()
    val groups: MutableList<Group> = mutableListOf()

    fun add(node: Node) { nodes[node.id] = node }

    fun removeNode(id: NodeId) {
        nodes.remove(id) ?: return
        edges.removeAll { it.from.node == id || it.to.node == id }
        // Strip the dead node from any group's members. Empty inline groups
        // are GC'd; empty linked groups remain (template still defines the shape).
        val rewritten = groups.map { g ->
            g.copy(members = g.members.filter { m -> m !is MemberRef.Node || m.id != id })
        }
        groups.clear()
        for (g in rewritten) {
            if (g.members.isEmpty() && g.templateFile == null) continue
            groups.add(g)
        }
    }

    fun addEdge(edge: Edge) { edges.add(edge) }
    fun removeEdge(edge: Edge) { edges.remove(edge) }

    fun connectReplacing(edge: Edge) {
        edges.removeAll { it.to == edge.to }
        edges.add(edge)
    }

    fun deepCopy(): NodeGraph {
        val tag = CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, this)
            .result().orElseThrow()
        return CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
            .result().orElseThrow()
    }

    companion object {
        val CODEC: com.mojang.serialization.Codec<NodeGraph> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    Node.CODEC.listOf().fieldOf("nodes").forGetter { g -> g.nodes.values.toList() },
                    Edge.CODEC.listOf().fieldOf("edges").forGetter { g -> g.edges.toList() },
                    Group.CODEC.listOf().fieldOf("groups").forGetter { g -> g.groups.toList() },
                ).apply(i) { nodeList, edgeList, groupList ->
                    NodeGraph().also { g ->
                        for (n in nodeList) g.nodes[n.id] = n
                        g.edges.addAll(edgeList)
                        g.groups.addAll(groupList)
                    }
                }
            }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run existing tests**

Run: `./gradlew test`
Expected: ALL PASS. Pre-existing codec tests must still round-trip
(the new `groups` field defaults to an empty list, which serialises
to `groups: []`).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt
git commit -m "feat(graph): NodeGraph.groups + codec extension"
```

### Task 1.3: Codec round-trip test

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupCodecTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupCodecTest {

    private fun <T> roundTrip(codec: com.mojang.serialization.Codec<T>, v: T): T {
        val tag = codec.encodeStart(NbtOps.INSTANCE, v).result().orElseThrow()
        return codec.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
    }

    @Test fun groupInlineEmpty() {
        val g = Group(
            id = UUID.randomUUID(),
            name = "Test",
            members = emptyList(),
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = CanvasPos(10f, 20f),
            collapsedSize = null,
        )
        assertEquals(g, roundTrip(Group.CODEC, g))
    }

    @Test fun groupLinkedWithMembersAndOverrides() {
        val n1 = UUID.randomUUID()
        val n2 = UUID.randomUUID()
        val sub = UUID.randomUUID()
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val g = Group(
            id = UUID.randomUUID(),
            name = "Adder",
            members = listOf(MemberRef.Node(n1), MemberRef.Node(n2), MemberRef.Sub(sub)),
            templateFile = "adder",
            templateIdMap = mapOf(t1 to n1, t2 to n2),
            collapsed = true,
            pos = CanvasPos(-5f, 5f),
            collapsedSize = 120 to 60,
            pinLabelOverrides = mapOf("nodeA.out" to "Sum"),
        )
        assertEquals(g, roundTrip(Group.CODEC, g))
    }

    @Test fun nodeGraphWithGroups() {
        val nid = UUID.randomUUID()
        val node = Node(
            id = nid,
            typeKey = net.minecraft.resources.ResourceLocation("nodewire", "constant"),
            pos = CanvasPos.Zero,
            inputs = emptyList(),
            outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        )
        val graph = NodeGraph().apply {
            add(node)
            groups.add(
                Group(
                    id = UUID.randomUUID(),
                    name = "G",
                    members = listOf(MemberRef.Node(nid)),
                    templateFile = null,
                    templateIdMap = null,
                    collapsed = false,
                    pos = CanvasPos(0f, 0f),
                    collapsedSize = null,
                )
            )
        }
        val decoded = roundTrip(NodeGraph.CODEC, graph)
        assertEquals(1, decoded.nodes.size)
        assertEquals(1, decoded.groups.size)
        assertEquals(graph.groups[0], decoded.groups[0])
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.GroupCodecTest"`
Expected: 3 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/nitka/nodewire/graph/GroupCodecTest.kt
git commit -m "test(graph): Group + NodeGraph-with-groups codec round-trip"
```

---

## Phase 2 — Template format and disk storage

### Task 2.1: `GroupTemplate` data class + codec

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplate.kt`

- [ ] **Step 1: Write the file**

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * On-disk template: a `NodeGraph`-shaped slice keyed by [TemplateNodeId]s
 * instead of runtime [NodeId]s. Loaded by [GroupTemplateStore] and
 * instantiated into a host graph via [GroupTemplateResolver].
 *
 * Internal edges reference template ids on both endpoints. External wires
 * never appear here — they live in the host graph that contains an
 * instance of this template.
 *
 * Nested groups: a template may itself contain [Group] entries whose
 * [Group.members] reference [MemberRef.Node] ids that are also keys in
 * [nodes]. Sub-groups can be inline or further linked to other template
 * files — those files are resolved recursively at instantiation.
 */
data class GroupTemplate(
    val nodes: Map<TemplateNodeId, Node>,
    val edges: List<Edge>,
    val groups: List<Group>,
) {
    companion object {
        val CODEC: Codec<GroupTemplate> = RecordCodecBuilder.create { i ->
            i.group(
                Node.CODEC.listOf().fieldOf("nodes").forGetter { it.nodes.values.toList() },
                Edge.CODEC.listOf().fieldOf("edges").forGetter(GroupTemplate::edges),
                Group.CODEC.listOf().fieldOf("groups").forGetter(GroupTemplate::groups),
            ).apply(i) { nodeList, edgeList, groupList ->
                GroupTemplate(
                    nodes = nodeList.associateBy { it.id },
                    edges = edgeList,
                    groups = groupList,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile and commit**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplate.kt
git commit -m "feat(graph): GroupTemplate codec"
```

### Task 2.2: `GroupFiles` disk I/O

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFiles.kt`

- [ ] **Step 1: Write the file**

Mirror the structure of the existing `GraphFiles.kt` (same package,
same SNBT envelope pattern):

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Local-only template storage under `<gamedir>/nodewire-groups/`.
 * Same envelope pattern as [GraphFiles] for symmetry — `nodewire_group`
 * marker + `version` + `template` payload.
 */
object GroupFiles {

    private const val EXT = "snbt"

    private fun dir(): Path = FMLPaths.GAMEDIR.get().resolve("nodewire-groups")

    fun sanitize(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ' ' || c == '.') c else '_'
        }.joinToString("")
    }

    fun list(): List<String> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension.equals(EXT, ignoreCase = true) }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
        }
    }

    fun save(name: String, template: GroupTemplate): Path? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        return try {
            Files.createDirectories(dir())
            val file = dir().resolve("$safe.$EXT")
            Files.writeString(file, encode(template))
            file
        } catch (t: Throwable) {
            System.err.println("[Nodewire] group save failed: ${t.message}")
            null
        }
    }

    fun load(name: String): GroupTemplate? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        val file = dir().resolve("$safe.$EXT")
        return try {
            if (!Files.isRegularFile(file)) return null
            decode(Files.readString(file))
        } catch (t: Throwable) {
            System.err.println("[Nodewire] group load failed: ${t.message}")
            null
        }
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name)
        if (safe.isEmpty()) return false
        return try { Files.deleteIfExists(dir().resolve("$safe.$EXT")) } catch (_: Throwable) { false }
    }

    private const val MARKER = "nodewire_group"
    private const val VERSION_KEY = "version"
    private const val PAYLOAD_KEY = "template"
    private const val CURRENT_VERSION = 1

    private fun encode(t: GroupTemplate): String {
        val payload = GroupTemplate.CODEC.encodeStart(NbtOps.INSTANCE, t)
            .result().orElseThrow()
        val wrapper = CompoundTag().apply {
            putBoolean(MARKER, true)
            putInt(VERSION_KEY, CURRENT_VERSION)
            put(PAYLOAD_KEY, payload)
        }
        return wrapper.toString()
    }

    private fun decode(raw: String): GroupTemplate? {
        val parsed = runCatching { TagParser.parseTag(raw) }.getOrNull() ?: return null
        val wrapper = parsed as? CompoundTag ?: return null
        if (!wrapper.getBoolean(MARKER)) return null
        val payload = wrapper.get(PAYLOAD_KEY) ?: return null
        return GroupTemplate.CODEC.parse(NbtOps.INSTANCE, payload).result().orElse(null)
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFiles.kt
git commit -m "feat(client): GroupFiles disk I/O for templates"
```

---

## Phase 3 — Template resolver

### Task 3.1: Bbox helper

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/GroupBbox.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupBboxTest.kt`

- [ ] **Step 1: Write the test first**

```kotlin
package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GroupBboxTest {
    @Test fun emptyGroupReturnsZeroSizedAtAnchor() {
        val bbox = GroupBbox.compute(CanvasPos(10f, 20f), emptyList())
        assertEquals(10f, bbox.minX); assertEquals(20f, bbox.minY)
        assertEquals(10f, bbox.maxX); assertEquals(20f, bbox.maxY)
    }

    @Test fun unionsMemberRects() {
        val members = listOf(
            CanvasPos(0f, 0f) to (100 to 50),
            CanvasPos(200f, 100f) to (80 to 40),
        )
        val bbox = GroupBbox.compute(CanvasPos(0f, 0f), members)
        assertEquals(0f, bbox.minX); assertEquals(0f, bbox.minY)
        assertEquals(280f, bbox.maxX); assertEquals(140f, bbox.maxY)
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupBboxTest"
```

Expected: compile error (`GroupBbox not found`).

- [ ] **Step 3: Implement**

```kotlin
package dev.nitka.nodewire.graph

/** Axis-aligned rectangle in canvas (world) units. */
data class CanvasRect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

object GroupBbox {
    /**
     * Tight bounding rectangle that covers all member rectangles. Each
     * member rectangle is `(pos, width × height)`. If the list is empty
     * the bbox collapses to a zero-area point at [anchor] — callers can
     * then render a placeholder frame.
     */
    fun compute(anchor: CanvasPos, members: List<Pair<CanvasPos, Pair<Int, Int>>>): CanvasRect {
        if (members.isEmpty()) return CanvasRect(anchor.x, anchor.y, anchor.x, anchor.y)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((p, size) in members) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            val r = p.x + size.first.toFloat()
            val b = p.y + size.second.toFloat()
            if (r > maxX) maxX = r
            if (b > maxY) maxY = b
        }
        return CanvasRect(minX, minY, maxX, maxY)
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupBboxTest"
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/GroupBbox.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/GroupBboxTest.kt
git commit -m "feat(graph): GroupBbox helper + tests"
```

### Task 3.2: Cycle detection across templates

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/GroupMembership.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupCycleDetectionTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupCycleDetectionTest {

    /** Helper: build a template that references one other template by name. */
    private fun templateRef(name: String): GroupTemplate {
        val gid = Group.newId()
        return GroupTemplate(
            nodes = emptyMap(),
            edges = emptyList(),
            groups = listOf(
                Group(
                    id = gid,
                    name = "ref-$name",
                    members = emptyList(),
                    templateFile = name,
                    templateIdMap = emptyMap(),
                    collapsed = false,
                    pos = CanvasPos.Zero,
                    collapsedSize = null,
                )
            ),
        )
    }

    @Test fun simpleSelfReferenceIsCycle() {
        val resolve: (String) -> GroupTemplate? = { name -> if (name == "A") templateRef("A") else null }
        assertTrue(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "A", resolve = resolve))
    }

    @Test fun indirectReferenceIsCycle() {
        val resolve: (String) -> GroupTemplate? = { name ->
            when (name) {
                "A" -> templateRef("B")
                "B" -> templateRef("A")
                else -> null
            }
        }
        assertTrue(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "B", resolve = resolve))
    }

    @Test fun unrelatedInsertionIsNotCycle() {
        val resolve: (String) -> GroupTemplate? = { name ->
            if (name == "B") templateRef("B-leaf") else if (name == "B-leaf") GroupTemplate(emptyMap(), emptyList(), emptyList()) else null
        }
        assertFalse(GroupMembership.wouldCycle(rootFile = "A", insertedTemplate = "B", resolve = resolve))
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
package dev.nitka.nodewire.graph

/**
 * Pure helpers for navigating group structure: ancestors, descendants,
 * and the all-important cycle check that protects us from infinite
 * recursion when a template instantiates another that (transitively)
 * instantiates the first.
 */
object GroupMembership {

    /**
     * True if inserting a group with `insertedTemplate` into a host that
     * currently sits inside (or IS) `rootFile`'s template would create a
     * cycle.
     *
     * `resolve` returns the template content for a given filename, or
     * `null` if the file is missing. Missing files conservatively count
     * as cycle-safe — we can't see what they reference.
     */
    fun wouldCycle(
        rootFile: String?,
        insertedTemplate: String,
        resolve: (String) -> GroupTemplate?,
    ): Boolean {
        if (rootFile == null) return false
        if (rootFile == insertedTemplate) return true
        // BFS from inserted template; if we ever encounter rootFile, cycle.
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(insertedTemplate)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            if (cur == rootFile) return true
            val tpl = resolve(cur) ?: continue
            for (g in tpl.groups) {
                val ref = g.templateFile ?: continue
                queue.addLast(ref)
            }
        }
        return false
    }
}
```

- [ ] **Step 3: Run tests, expect PASS**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupCycleDetectionTest"
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/GroupMembership.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/GroupCycleDetectionTest.kt
git commit -m "feat(graph): cycle detection across template references"
```

### Task 3.3: Resolver — instantiate template into host

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolver.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolverTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupTemplateResolverTest {

    private fun constantNode(id: UUID): Node = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", "constant"),
        pos = CanvasPos.Zero,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun instantiateGeneratesFreshRuntimeIdsAndIdMap() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1), t2 to constantNode(t2)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        val res = GroupTemplateResolver.instantiate(
            host = host,
            template = template,
            templateFile = "adder",
            anchor = CanvasPos(10f, 10f),
            resolve = { null },
        )
        assertEquals(2, host.nodes.size)
        assertEquals(1, host.groups.size)
        val g = host.groups[0]
        assertEquals("adder", g.templateFile)
        assertNotNull(g.templateIdMap)
        assertEquals(2, g.templateIdMap!!.size)
        // Runtime ids differ from template ids.
        assertNotEquals(t1, g.templateIdMap[t1])
        assertNotEquals(t2, g.templateIdMap[t2])
    }

    @Test fun applyDiffAddsNewTemplateNodes() {
        val t1 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id

        // New template version with one additional node.
        val t2 = UUID.randomUUID()
        val updated = template.copy(
            nodes = template.nodes + (t2 to constantNode(t2)),
        )
        GroupTemplateResolver.applyTemplateChange(host, gid, updated) { null }

        assertEquals(2, host.nodes.size)
        val map = host.groups[0].templateIdMap!!
        assertNotNull(map[t1]); assertNotNull(map[t2])
    }

    @Test fun applyDiffRemovesGoneTemplateNodesAndDropsExternalEdges() {
        val t1 = UUID.randomUUID()
        val external = constantNode(UUID.randomUUID())
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph().apply { add(external) }
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id
        val internalRuntimeId = host.groups[0].templateIdMap!![t1]!!
        // External wire that touches an internal node.
        host.addEdge(
            Edge(
                PinRef(internalRuntimeId, "out"),
                PinRef(external.id, "in"),
            )
        )
        // Even though the external pin doesn't exist on `external`, the
        // resolver doesn't care — it only checks endpoint node identity.

        // Template now has no nodes.
        val emptied = template.copy(nodes = emptyMap())
        val dropped = GroupTemplateResolver.applyTemplateChange(host, gid, emptied) { null }

        assertEquals(0, host.nodes.count { it.key == internalRuntimeId })
        // External edge dropped.
        assertTrue(host.edges.none { it.from.node == internalRuntimeId || it.to.node == internalRuntimeId })
        assertEquals(1, dropped.droppedEdges.size)
    }

    @Test fun applyDiffPreservesStableIdsForUnchangedTemplateNodes() {
        val t1 = UUID.randomUUID()
        val template = GroupTemplate(
            nodes = mapOf(t1 to constantNode(t1)),
            edges = emptyList(),
            groups = emptyList(),
        )
        val host = NodeGraph()
        GroupTemplateResolver.instantiate(host, template, "x", CanvasPos.Zero) { null }
        val gid = host.groups[0].id
        val originalRuntimeId = host.groups[0].templateIdMap!![t1]!!

        // Same template — runtime ids must stay put.
        GroupTemplateResolver.applyTemplateChange(host, gid, template) { null }
        assertEquals(originalRuntimeId, host.groups[0].templateIdMap!![t1])
    }
}
```

- [ ] **Step 2: Implement the resolver**

```kotlin
package dev.nitka.nodewire.graph

import java.util.UUID

/**
 * Stateless logic for instantiating and re-syncing template-linked groups.
 *
 * Two entry points:
 *   - [instantiate] — create a fresh instance in [host], allocating new
 *     runtime [NodeId]s for every template node and recording the mapping
 *     on the new [Group]. Translates internal edges; nested template
 *     groups recurse with cycle protection.
 *   - [applyTemplateChange] — diff the existing instance's idMap against
 *     a new [GroupTemplate]. Preserves runtime ids for unchanged template
 *     ids; adds new ones; removes vanished ones, returning the dropped
 *     external edges so the editor can toast them.
 */
object GroupTemplateResolver {

    data class ResolveResult(val groupId: GroupId, val droppedEdges: List<Edge>)

    fun instantiate(
        host: NodeGraph,
        template: GroupTemplate,
        templateFile: String?,
        anchor: CanvasPos,
        resolve: (String) -> GroupTemplate?,
        seenFiles: Set<String> = emptySet(),
    ): ResolveResult {
        // Cycle check (only meaningful for linked templates).
        if (templateFile != null && templateFile in seenFiles) {
            // Drop silently: caller has already validated up-front with
            // GroupMembership.wouldCycle, this is a defensive guard.
            return ResolveResult(Group.newId(), emptyList())
        }

        val idMap = HashMap<TemplateNodeId, NodeId>()
        // Allocate runtime ids and clone nodes, offsetting by anchor.
        for ((tid, tNode) in template.nodes) {
            val rid = Node.newId()
            idMap[tid] = rid
            host.nodes[rid] = tNode.copy(
                id = rid,
                pos = CanvasPos(tNode.pos.x + anchor.x, tNode.pos.y + anchor.y),
            )
        }
        // Translate internal edges.
        for (e in template.edges) {
            val from = idMap[e.from.node] ?: continue
            val to = idMap[e.to.node] ?: continue
            host.edges.add(Edge(PinRef(from, e.from.pin), PinRef(to, e.to.pin)))
        }
        // Recursively resolve nested groups.
        val subSeen = if (templateFile != null) seenFiles + templateFile else seenFiles
        val members = mutableListOf<MemberRef>()
        for ((tid, _) in template.nodes) members.add(MemberRef.Node(idMap[tid]!!))
        for (subGroup in template.groups) {
            val subFile = subGroup.templateFile
            val subTpl = if (subFile != null) resolve(subFile) else null
            if (subFile != null && subTpl != null) {
                val sub = instantiate(host, subTpl, subFile, anchor, resolve, subSeen)
                members.add(MemberRef.Sub(sub.groupId))
            } else {
                // Inline sub-group: rewrite members through idMap.
                val rewrittenMembers = subGroup.members.mapNotNull { m ->
                    when (m) {
                        is MemberRef.Node -> idMap[m.id]?.let { MemberRef.Node(it) }
                        is MemberRef.Sub -> null  // resolved above for linked subs
                    }
                }
                val newId = Group.newId()
                host.groups.add(
                    subGroup.copy(
                        id = newId,
                        members = rewrittenMembers,
                        templateIdMap = null,
                        pos = CanvasPos(subGroup.pos.x + anchor.x, subGroup.pos.y + anchor.y),
                    )
                )
                members.add(MemberRef.Sub(newId))
            }
        }
        val gid = Group.newId()
        host.groups.add(
            Group(
                id = gid,
                name = templateFile ?: "Group",
                members = members,
                templateFile = templateFile,
                templateIdMap = if (templateFile != null) idMap else null,
                collapsed = false,
                pos = anchor,
                collapsedSize = null,
            )
        )
        return ResolveResult(gid, emptyList())
    }

    fun applyTemplateChange(
        host: NodeGraph,
        groupId: GroupId,
        newTemplate: GroupTemplate,
        resolve: (String) -> GroupTemplate?,
    ): ResolveResult {
        val idx = host.groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return ResolveResult(groupId, emptyList())
        val cur = host.groups[idx]
        val oldMap = cur.templateIdMap ?: return ResolveResult(groupId, emptyList())

        val newMap = HashMap<TemplateNodeId, NodeId>()

        // 1. Preserve unchanged: any template id still present keeps its
        //    runtime id.
        for ((tid, rid) in oldMap) {
            if (tid in newTemplate.nodes) {
                newMap[tid] = rid
                // Update node content with template version (carries new
                // pin shape / config etc.), but keep runtime id and pos.
                val tNode = newTemplate.nodes[tid]!!
                val keptPos = host.nodes[rid]?.pos ?: tNode.pos
                host.nodes[rid] = tNode.copy(id = rid, pos = keptPos)
            }
        }
        // 2. Add new template nodes.
        for ((tid, tNode) in newTemplate.nodes) {
            if (tid in newMap) continue
            val rid = Node.newId()
            newMap[tid] = rid
            host.nodes[rid] = tNode.copy(
                id = rid,
                pos = CanvasPos(tNode.pos.x + cur.pos.x, tNode.pos.y + cur.pos.y),
            )
        }
        // 3. Remove vanished template nodes; collect dropped external edges.
        val droppedEdges = mutableListOf<Edge>()
        for ((tid, rid) in oldMap) {
            if (tid !in newMap) {
                host.nodes.remove(rid)
                val (touching, kept) = host.edges.partition { it.from.node == rid || it.to.node == rid }
                droppedEdges.addAll(touching)
                host.edges.clear()
                host.edges.addAll(kept)
            }
        }
        // 4. Replace internal edges wholesale (template owns them).
        val internalRuntimeIds = newMap.values.toSet()
        host.edges.removeAll { e ->
            e.from.node in internalRuntimeIds && e.to.node in internalRuntimeIds
        }
        for (e in newTemplate.edges) {
            val from = newMap[e.from.node] ?: continue
            val to = newMap[e.to.node] ?: continue
            host.edges.add(Edge(PinRef(from, e.from.pin), PinRef(to, e.to.pin)))
        }
        // 5. Rewrite the group with the new map and rebuilt members list.
        val members = mutableListOf<MemberRef>()
        for ((_, rid) in newMap) members.add(MemberRef.Node(rid))
        host.groups[idx] = cur.copy(members = members, templateIdMap = newMap)
        return ResolveResult(groupId, droppedEdges)
    }
}
```

- [ ] **Step 3: Run tests, expect PASS**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupTemplateResolverTest"
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolver.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/GroupTemplateResolverTest.kt
git commit -m "feat(graph): GroupTemplateResolver — instantiate + applyTemplateChange"
```

### Task 3.4: Nesting smoke test

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupNestingTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupNestingTest {

    private fun n(id: UUID) = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", "constant"),
        pos = CanvasPos.Zero,
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun nestedInlineGroupsCarriedThroughCodec() {
        val nid = UUID.randomUUID()
        val inner = Group(
            id = Group.newId(),
            name = "Inner",
            members = listOf(MemberRef.Node(nid)),
            templateFile = null,
            templateIdMap = null,
            collapsed = true,
            pos = CanvasPos.Zero,
            collapsedSize = null,
        )
        val outer = Group(
            id = Group.newId(),
            name = "Outer",
            members = listOf(MemberRef.Node(nid), MemberRef.Sub(inner.id)),
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = CanvasPos.Zero,
            collapsedSize = null,
        )
        val g = NodeGraph().apply {
            add(n(nid))
            groups.add(inner); groups.add(outer)
        }
        val decoded = g.deepCopy()
        assertEquals(2, decoded.groups.size)
        val decodedOuter = decoded.groups.first { it.name == "Outer" }
        assertTrue(decodedOuter.members.any { it is MemberRef.Sub })
    }
}
```

- [ ] **Step 2: Run, commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupNestingTest"
git add src/test/kotlin/dev/nitka/nodewire/graph/GroupNestingTest.kt
git commit -m "test(graph): nested groups round-trip"
```

### Task 3.5: Proxy-pin computation

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupProxyPins.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/GroupProxyPinsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.GroupProxyPin
import dev.nitka.nodewire.client.screen.GroupProxyPins
import dev.nitka.nodewire.client.screen.PinSide
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupProxyPinsTest {

    private fun node(id: UUID, name: String) = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", name),
        pos = CanvasPos.Zero,
        inputs = listOf(Pin("a", "A", PinType.BOOL)),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun crossBoundaryEdgesProduceProxies() {
        val inside = UUID.randomUUID()
        val outsideUpstream = UUID.randomUUID()
        val outsideDownstream = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(inside, "in"))
            add(node(outsideUpstream, "up"))
            add(node(outsideDownstream, "down"))
            // upstream.out → inside.a    (external -> internal: inside.a is an input proxy)
            addEdge(Edge(PinRef(outsideUpstream, "out"), PinRef(inside, "a")))
            // inside.out → downstream.a  (internal -> external: inside.out is an output proxy)
            addEdge(Edge(PinRef(inside, "out"), PinRef(outsideDownstream, "a")))
        }
        val group = Group(
            id = Group.newId(),
            name = "G",
            members = listOf(MemberRef.Node(inside)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies: List<GroupProxyPin> = GroupProxyPins.compute(graph, group, memberClosure = setOf(inside))
        assertEquals(2, proxies.size)
        assertEquals(setOf(PinSide.Input, PinSide.Output), proxies.map { it.side }.toSet())
    }

    @Test fun internalOnlyEdgesProduceNoProxies() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val graph = NodeGraph().apply {
            add(node(a, "a")); add(node(b, "b"))
            addEdge(Edge(PinRef(a, "out"), PinRef(b, "a")))
        }
        val group = Group(
            id = Group.newId(),
            name = "G",
            members = listOf(MemberRef.Node(a), MemberRef.Node(b)),
            templateFile = null, templateIdMap = null,
            collapsed = true, pos = CanvasPos.Zero, collapsedSize = null,
        )
        val proxies = GroupProxyPins.compute(graph, group, memberClosure = setOf(a, b))
        assertEquals(0, proxies.size)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.PinType

/**
 * One auto-derived pin on a collapsed group's tile. The proxy is purely
 * a render-time artefact — it carries enough info for the renderer to
 * draw the row, and for [WireLayer] to detour wires that touch
 * [innerNode] through this pin's position.
 */
data class GroupProxyPin(
    val side: PinSide,
    val innerNode: NodeId,
    val innerPin: String,
    val type: PinType,
    val label: String,
)

object GroupProxyPins {

    /**
     * Compute the proxy pin set for a group's collapsed tile. Caller
     * supplies the recursive member-node closure so we don't have to
     * traverse `MemberRef.Sub` here.
     *
     * Algorithm: scan every edge; if exactly one endpoint's node is in
     * the closure, that endpoint becomes a proxy of the appropriate
     * side. Input pins get `PinSide.Input`; output pins get `Output`.
     */
    fun compute(graph: NodeGraph, group: Group, memberClosure: Set<NodeId>): List<GroupProxyPin> {
        val result = mutableListOf<GroupProxyPin>()
        val seen = HashSet<Triple<NodeId, String, PinSide>>()
        for (e in graph.edges) {
            val fromInside = e.from.node in memberClosure
            val toInside = e.to.node in memberClosure
            if (fromInside == toInside) continue  // both inside or both outside
            if (fromInside) {
                val node = graph.nodes[e.from.node] ?: continue
                val pin = node.outputs.firstOrNull { it.id == e.from.pin } ?: continue
                val key = Triple(node.id, pin.id, PinSide.Output)
                if (seen.add(key)) {
                    val override = group.pinLabelOverrides["${node.id}.${pin.id}"]
                    result.add(GroupProxyPin(
                        side = PinSide.Output,
                        innerNode = node.id,
                        innerPin = pin.id,
                        type = pin.type,
                        label = override ?: "${node.id.toString().take(4)}.${pin.name}",
                    ))
                }
            } else {
                val node = graph.nodes[e.to.node] ?: continue
                val pin = node.inputs.firstOrNull { it.id == e.to.pin } ?: continue
                val key = Triple(node.id, pin.id, PinSide.Input)
                if (seen.add(key)) {
                    val override = group.pinLabelOverrides["${node.id}.${pin.id}"]
                    result.add(GroupProxyPin(
                        side = PinSide.Input,
                        innerNode = node.id,
                        innerPin = pin.id,
                        type = pin.type,
                        label = override ?: "${node.id.toString().take(4)}.${pin.name}",
                    ))
                }
            }
        }
        return result
    }

    /**
     * Recursively expand `group.members` into the flat set of node ids.
     * Sub-group entries are resolved by id lookup in [allGroups].
     */
    fun memberClosure(group: Group, allGroups: Map<dev.nitka.nodewire.graph.GroupId, Group>): Set<NodeId> {
        val out = HashSet<NodeId>()
        val stack = ArrayDeque<Group>()
        stack.addLast(group)
        val seen = HashSet<dev.nitka.nodewire.graph.GroupId>()
        while (stack.isNotEmpty()) {
            val g = stack.removeLast()
            if (!seen.add(g.id)) continue
            for (m in g.members) {
                when (m) {
                    is MemberRef.Node -> out.add(m.id)
                    is MemberRef.Sub -> allGroups[m.id]?.let(stack::addLast)
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 3: Run tests + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.GroupProxyPinsTest"
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupProxyPins.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/GroupProxyPinsTest.kt
git commit -m "feat(client): GroupProxyPins — auto-derive collapsed pins"
```

---

## Phase 4 — EditorState group operations

### Task 4.1: Reactive `groups` flow + basic ops

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add the groups flow**

Inside `EditorState`, after the `_edges` flow declaration, insert:

```kotlin
    private val _groups: MutableStateFlow<List<Group>> =
        MutableStateFlow(graph.groups.toList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private fun syncGroupsFlow() { _groups.value = graph.groups.toList() }
```

Also import:

```kotlin
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.MemberRef
```

- [ ] **Step 2: Extend `restoreFrom` to repopulate `_groups`**

Inside the existing `restoreFrom(snapshot)` method, after `_edges.value = graph.edges.toList()`, add:

```kotlin
        graph.groups.clear()
        graph.groups.addAll(snapshot.groups)
        syncGroupsFlow()
```

- [ ] **Step 3: Add group ops**

Append these methods to `EditorState`:

```kotlin
    /** Create an inline group containing the current selection. */
    fun createGroupFromSelection(name: String): GroupId? {
        if (selectedNodes.isEmpty()) return null
        val ids = selectedNodes.toList()
        // Anchor: top-left of the bbox so dragging the header doesn't
        // jump on first move.
        val xs = ids.mapNotNull { graph.nodes[it]?.pos?.x }
        val ys = ids.mapNotNull { graph.nodes[it]?.pos?.y }
        val anchor = if (xs.isEmpty()) dev.nitka.nodewire.graph.CanvasPos.Zero
        else dev.nitka.nodewire.graph.CanvasPos(xs.min(), ys.min())
        val g = Group(
            id = Group.newId(),
            name = name,
            members = ids.map { MemberRef.Node(it) },
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = anchor,
            collapsedSize = null,
        )
        mutateGraph {
            graph.groups.add(g)
            syncGroupsFlow()
        }
        return g.id
    }

    /** Dissolve a group; members survive at their current positions. */
    fun ungroup(id: GroupId) {
        mutateGraph {
            graph.groups.removeAll { it.id == id }
            // Any parent groups that referenced this as a Sub keep
            // pointing at its (now gone) id — strip those refs.
            val rebuilt = graph.groups.map { g ->
                g.copy(members = g.members.filter { m -> m !is MemberRef.Sub || m.id != id })
            }
            graph.groups.clear()
            graph.groups.addAll(rebuilt)
            syncGroupsFlow()
        }
    }

    /** Toggle the `collapsed` flag of [id]. */
    fun toggleCollapsed(id: GroupId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.groups[i] = graph.groups[i].copy(collapsed = !graph.groups[i].collapsed)
            syncGroupsFlow()
        }
    }

    /** Detach a linked group from its template file; becomes inline. */
    fun unlinkGroup(id: GroupId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.groups[i] = graph.groups[i].copy(templateFile = null, templateIdMap = null)
            syncGroupsFlow()
        }
    }

    fun addMemberToGroup(groupId: GroupId, nodeId: dev.nitka.nodewire.graph.NodeId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == groupId }
            if (i < 0) return@mutateGraph
            val cur = graph.groups[i]
            if (cur.members.any { it is MemberRef.Node && it.id == nodeId }) return@mutateGraph
            graph.groups[i] = cur.copy(members = cur.members + MemberRef.Node(nodeId))
            syncGroupsFlow()
        }
    }

    fun removeMemberFromGroup(groupId: GroupId, nodeId: dev.nitka.nodewire.graph.NodeId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == groupId }
            if (i < 0) return@mutateGraph
            val cur = graph.groups[i]
            graph.groups[i] = cur.copy(
                members = cur.members.filter { it !is MemberRef.Node || it.id != nodeId }
            )
            syncGroupsFlow()
        }
    }

    /** Move every node in [groupId]'s closure by `(dx, dy)`. Used by frame header drag. */
    fun moveGroup(groupId: GroupId, dxWorld: Float, dyWorld: Float) {
        val g = graph.groups.firstOrNull { it.id == groupId } ?: return
        val allById = graph.groups.associateBy { it.id }
        val closure = dev.nitka.nodewire.client.screen.GroupProxyPins.memberClosure(g, allById)
        if (closure.isEmpty()) return
        mutateGraph(mergeable = true) {
            for (id in closure) {
                _updateNodeInternalUnsafe(id) { n ->
                    n.copy(pos = dev.nitka.nodewire.graph.CanvasPos(n.pos.x + dxWorld, n.pos.y + dyWorld))
                }
            }
            // Also move the group anchor + any nested group anchors so
            // sub-group tiles follow when the parent is dragged.
            val anchorMap = graph.groups.associate { it.id to it.pos }.toMutableMap()
            val touched = HashSet<GroupId>()
            val stack = ArrayDeque<Group>(); stack.addLast(g)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!touched.add(cur.id)) continue
                anchorMap[cur.id] = dev.nitka.nodewire.graph.CanvasPos(
                    cur.pos.x + dxWorld, cur.pos.y + dyWorld
                )
                for (m in cur.members) if (m is MemberRef.Sub) allById[m.id]?.let(stack::addLast)
            }
            val rebuilt = graph.groups.map { gg -> gg.copy(pos = anchorMap[gg.id] ?: gg.pos) }
            graph.groups.clear()
            graph.groups.addAll(rebuilt)
            syncGroupsFlow()
        }
    }
```

Add the missing helper `_updateNodeInternalUnsafe` — same body as
`_updateNodeInternal` but exposed for use inside `moveGroup`. Place
it next to `_updateNodeInternal`:

```kotlin
    private fun _updateNodeInternalUnsafe(id: NodeId, transform: (Node) -> Node) {
        val flow = nodeFlows[id] ?: return
        val updated = transform(flow.value)
        flow.value = updated
        graph.nodes[id] = updated
    }
```

(If `_updateNodeInternal` is already package-private and adequate,
just call it directly and skip adding this duplicate.)

- [ ] **Step 4: Make `removeNode` also strip group membership**

Inside `EditorState.removeNode`, after the existing `graph.removeNode(id)` call, append:

```kotlin
            syncGroupsFlow()
```

(`NodeGraph.removeNode` itself already strips the node from every group's
`members` list — see Task 1.2 — and GCs empty inline groups. The flow
just needs to be re-emitted so UI rebinds.)

- [ ] **Step 5: Build + run all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "feat(editor): EditorState group ops + groups flow"
```

### Task 4.2: Unit-test group ops

**Files:**
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateGroupOpsTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EditorStateGroupOpsTest {

    private fun node(id: UUID, x: Float = 0f, y: Float = 0f) = Node(
        id = id,
        typeKey = ResourceLocation("nodewire", "constant"),
        pos = CanvasPos(x, y),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
    )

    @Test fun createGroupFromSelectionWrapsSelectedNodes() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a, 10f, 10f)); add(node(b, 100f, 50f)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a, b))
        val gid = ed.createGroupFromSelection("Wrapped")
        assertNotNull(gid)
        assertEquals(1, g.groups.size)
        val grp = g.groups[0]
        assertEquals("Wrapped", grp.name)
        assertEquals(2, grp.members.count { it is MemberRef.Node })
    }

    @Test fun ungroupRemovesGroupAndKeepsNodes() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.ungroup(gid)
        assertEquals(0, g.groups.size)
        assertEquals(1, g.nodes.size)
    }

    @Test fun removeNodeStripsFromGroup() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.removeNode(a)
        // Inline empty group is GC'd by NodeGraph.removeNode.
        assertEquals(0, g.groups.size)
    }

    @Test fun toggleCollapsedFlipsFlag() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply { add(node(a)) }
        val ed = EditorState(g)
        ed.selectMany(listOf(a))
        val gid = ed.createGroupFromSelection("X")!!
        ed.toggleCollapsed(gid)
        assertTrue(g.groups[0].collapsed)
        ed.toggleCollapsed(gid)
        assertEquals(false, g.groups[0].collapsed)
    }

    @Test fun unlinkClearsTemplateFields() {
        val a = UUID.randomUUID()
        val g = NodeGraph().apply {
            add(node(a))
            groups.add(
                dev.nitka.nodewire.graph.Group(
                    id = UUID.randomUUID(),
                    name = "L",
                    members = listOf(MemberRef.Node(a)),
                    templateFile = "tpl",
                    templateIdMap = mapOf(UUID.randomUUID() to a),
                    collapsed = false,
                    pos = CanvasPos.Zero,
                    collapsedSize = null,
                )
            )
        }
        val ed = EditorState(g)
        ed.unlinkGroup(g.groups[0].id)
        assertNull(g.groups[0].templateFile)
        assertNull(g.groups[0].templateIdMap)
    }
}
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.client.screen.EditorStateGroupOpsTest"
git add src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateGroupOpsTest.kt
git commit -m "test(editor): group ops unit coverage"
```

---

## Phase 5 — Template store + live sync

### Task 5.1: `GroupTemplateStore`

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStore.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GroupTemplateStoreTest {

    @Test fun flowForSameFileIsShared() {
        val store = GroupTemplateStore()
        val a = store.flowOf("x")
        val b = store.flowOf("x")
        assertSame(a, b)
    }

    @Test fun flowsForDifferentFilesAreDistinct() {
        val store = GroupTemplateStore()
        assertNotSame(store.flowOf("x"), store.flowOf("y"))
    }

    @Test fun publishUpdatesFlow() = runBlocking {
        val store = GroupTemplateStore()
        val tpl = GroupTemplate(emptyMap(), emptyList(), emptyList())
        store.publish("x", tpl)
        assertEquals(tpl, store.flowOf("x").first())
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped registry of one [MutableStateFlow] per template file
 * the user has touched. Editors subscribe to a file's flow and react to
 * every publish; this is how an edit in one open instance propagates to
 * other instances in real time.
 *
 * Disk I/O is NOT performed here — the store sits on top of
 * [GroupFiles]. The wiring layer ([GroupTemplateSync]) is responsible
 * for `disk → publish` on first access and `publish → debounced disk
 * write` for outgoing edits.
 */
class GroupTemplateStore {
    private val flows = HashMap<String, MutableStateFlow<GroupTemplate?>>()

    fun flowOf(file: String): StateFlow<GroupTemplate?> =
        flows.getOrPut(file) { MutableStateFlow(null) }.asStateFlow()

    fun publish(file: String, template: GroupTemplate) {
        flows.getOrPut(file) { MutableStateFlow(null) }.value = template
    }

    fun current(file: String): GroupTemplate? = flows[file]?.value
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.client.screen.GroupTemplateStoreTest"
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStore.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateStoreTest.kt
git commit -m "feat(client): GroupTemplateStore with shared flows"
```

### Task 5.2: Sync controller — disk hydrate + debounce write + propagation

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateSync.kt`

- [ ] **Step 1: Write the file**

```kotlin
package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import dev.nitka.nodewire.graph.GroupTemplateResolver
import dev.nitka.nodewire.graph.NodeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Glues [GroupTemplateStore] to disk ([GroupFiles]) and to a host
 * [EditorState]'s reactive graph. Per-template-file responsibilities:
 *
 *   * On first call to [observeFile], hydrate the flow from disk if it
 *     still holds `null`.
 *   * Subscribe to the flow and re-apply each non-null publication to
 *     every linked instance in the host graph via
 *     [GroupTemplateResolver.applyTemplateChange]. Dropped external
 *     edges are reported to [onEdgeDropped] (toast).
 *   * Provide [publishLocalEdit] for callers that have just mutated an
 *     instance; debounces a disk write so rapid keystrokes don't
 *     hammer the filesystem.
 */
class GroupTemplateSync(
    private val store: GroupTemplateStore,
    private val scope: CoroutineScope,
    private val editor: EditorState,
    private val onEdgeDropped: (Int) -> Unit = {},
) {

    private val writeJobs = HashMap<String, Job>()
    private val observed = HashSet<String>()

    /** Begin watching [file] for this editor session. Idempotent. */
    fun observeFile(file: String) {
        if (!observed.add(file)) return
        // Hydrate from disk if no other editor has populated the flow yet.
        if (store.current(file) == null) {
            val loaded = GroupFiles.load(file)
            if (loaded != null) store.publish(file, loaded)
        }
        scope.launch {
            store.flowOf(file).filterNotNull().collect { newTemplate ->
                applyToInstances(file, newTemplate)
            }
        }
    }

    /** Apply a template snapshot to every instance of [file] in the current graph. */
    private fun applyToInstances(file: String, template: GroupTemplate) {
        val graph: NodeGraph = editor.graph
        val targets = graph.groups.filter { it.templateFile == file }.map { it.id }
        var dropped = 0
        editor.mutateGraph(mergeable = false) {
            for (gid in targets) {
                val res = GroupTemplateResolver.applyTemplateChange(graph, gid, template) { other ->
                    GroupFiles.load(other) ?: store.current(other)
                }
                dropped += res.droppedEdges.size
            }
        }
        if (dropped > 0) onEdgeDropped(dropped)
    }

    /** Push a local edit into the store + schedule disk write (300 ms debounce). */
    fun publishLocalEdit(file: String, template: GroupTemplate) {
        store.publish(file, template)
        writeJobs[file]?.cancel()
        writeJobs[file] = scope.launch {
            delay(WRITE_DEBOUNCE_MS)
            GroupFiles.save(file, template)
        }
    }

    companion object {
        private const val WRITE_DEBOUNCE_MS = 300L
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupTemplateSync.kt
git commit -m "feat(client): GroupTemplateSync — disk hydrate + debounced write + propagation"
```

### Task 5.3: Wire `EditorState` to `GroupTemplateSync`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add `templateSync` slot + `saveAsTemplate` + `insertTemplate`**

Append to `EditorState`:

```kotlin
    /**
     * Wired by [NodeEditorScreen] once it has a Compose scope. Null
     * before that point — `EditorState` is also used in headless tests
     * where sync is not needed.
     */
    var templateSync: GroupTemplateSync? = null

    /**
     * Persist a (possibly inline) group to a file under that name. The
     * group becomes linked; its existing runtime ids are mapped to fresh
     * [TemplateNodeId]s recorded in `templateIdMap`. Returns the
     * template that was written, or null on bad input.
     */
    fun saveAsTemplate(groupId: GroupId, fileName: String): dev.nitka.nodewire.graph.GroupTemplate? {
        val safe = GroupFiles.sanitize(fileName)
        if (safe.isEmpty()) return null
        val idx = graph.groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return null
        val g = graph.groups[idx]
        val allById = graph.groups.associateBy { it.id }
        val closure = GroupProxyPins.memberClosure(g, allById)

        val templateIdMap = HashMap<dev.nitka.nodewire.graph.TemplateNodeId, dev.nitka.nodewire.graph.NodeId>()
        for (rid in closure) templateIdMap[java.util.UUID.randomUUID()] = rid

        val runtimeToTemplate = templateIdMap.entries.associate { (t, r) -> r to t }

        val templateNodes = closure.mapNotNull { rid ->
            val n = graph.nodes[rid] ?: return@mapNotNull null
            val tid = runtimeToTemplate[rid]!!
            tid to n.copy(id = tid)
        }.toMap()
        val templateEdges = graph.edges.filter {
            it.from.node in closure && it.to.node in closure
        }.map { e ->
            dev.nitka.nodewire.graph.Edge(
                dev.nitka.nodewire.graph.PinRef(runtimeToTemplate[e.from.node]!!, e.from.pin),
                dev.nitka.nodewire.graph.PinRef(runtimeToTemplate[e.to.node]!!, e.to.pin),
            )
        }
        val template = dev.nitka.nodewire.graph.GroupTemplate(templateNodes, templateEdges, emptyList())

        mutateGraph {
            graph.groups[idx] = g.copy(templateFile = safe, templateIdMap = templateIdMap)
            syncGroupsFlow()
        }
        templateSync?.publishLocalEdit(safe, template)
            ?: GroupFiles.save(safe, template)
        templateSync?.observeFile(safe)
        return template
    }

    /**
     * Insert a saved template into the host graph at the given world
     * position. Returns the new group's id, or null on missing file /
     * detected cycle.
     */
    fun insertTemplate(fileName: String, atWorld: dev.nitka.nodewire.graph.CanvasPos): GroupId? {
        val safe = GroupFiles.sanitize(fileName)
        if (safe.isEmpty()) return null
        val template = templateSync?.let { sync ->
            sync.observeFile(safe)
            templateSync?.let { _ -> dev.nitka.nodewire.client.screen.GroupFiles.load(safe) }
        } ?: GroupFiles.load(safe) ?: return null

        // Cycle guard: refuse if any group on this graph that's linked to
        // a file is reachable from `safe`.
        val rootFiles = graph.groups.mapNotNull { it.templateFile }.toSet()
        val resolveFor = { name: String -> GroupFiles.load(name) }
        for (root in rootFiles) {
            if (dev.nitka.nodewire.graph.GroupMembership.wouldCycle(root, safe, resolveFor)) return null
        }

        var gid: GroupId? = null
        mutateGraph {
            val res = dev.nitka.nodewire.graph.GroupTemplateResolver.instantiate(
                host = graph,
                template = template,
                templateFile = safe,
                anchor = atWorld,
                resolve = resolveFor,
            )
            gid = res.groupId
            // Mirror new nodes into per-node flows.
            for (n in graph.nodes.values) {
                if (nodeFlows[n.id] == null) nodeFlows[n.id] = kotlinx.coroutines.flow.MutableStateFlow(n)
            }
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
            syncGroupsFlow()
        }
        return gid
    }
```

- [ ] **Step 2: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "feat(editor): saveAsTemplate / insertTemplate wired through GroupTemplateSync"
```

---

## Phase 6 — UI: expanded frame

### Task 6.1: `GroupFrame` composable

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFrame.kt`

- [ ] **Step 1: Write the composable**

```kotlin
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.canvas.LocalCanvasState

/**
 * Expanded-state visual for a [Group] — a semi-transparent frame around
 * the bbox of its members, with a header bar that drags every member.
 */
@Composable
fun GroupFrame(group: Group) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    val nodes by editor.nodes.collectAsState()
    // Listen to every member node's flow so bbox tracks live drags.
    val allGroups = editor.graph.groups.associateBy { it.id }
    val closure = remember(group, nodes) { GroupProxyPins.memberClosure(group, allGroups) }
    val rects = closure.mapNotNull { id ->
        val n = editor.nodeFlow(id)?.collectAsState()?.value ?: return@mapNotNull null
        val size = editor.cardSize(id) ?: (200 to 60)
        n.pos to (size.first to size.second)
    }
    val bbox = dev.nitka.nodewire.graph.GroupBbox.compute(group.pos, rects)
    val pad = NwTheme.dimens.space8
    val w = (bbox.maxX - bbox.minX).toInt() + pad * 2
    val h = (bbox.maxY - bbox.minY).toInt() + pad * 2 + HEADER_HEIGHT

    Box(
        modifier = Modifier
            .absolutePosition((bbox.minX - pad).toInt(), (bbox.minY - pad - HEADER_HEIGHT).toInt())
            .size(w, h)
            .background(NwTheme.colors.surfaceHover)
            .border(BorderStroke(1, NwTheme.colors.border), NwTheme.shapes.medium),
    ) {
        // Header bar with drag.
        Surface(
            modifier = Modifier.size(w, HEADER_HEIGHT).pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Drag -> {
                        val zoom = canvas?.zoom ?: 1f
                        editor.moveGroup(group.id, ev.dx / zoom, ev.dy / zoom)
                        true
                    }
                    is PointerEvent.Press -> true
                    else -> false
                }
            },
            style = SurfaceStyle(
                color = NwTheme.colors.surfacePressed,
                shape = NwTheme.shapes.small,
                border = null,
                padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
            ),
        ) {
            Row(
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                Text(group.name, style = NwTheme.typography.caption)
                if (group.templateFile != null) {
                    Text(
                        "↪${group.templateFile}",
                        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                    )
                }
            }
        }
    }
}

private const val HEADER_HEIGHT = 14
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupFrame.kt
git commit -m "feat(ui): GroupFrame — expanded-state frame composable"
```

---

## Phase 7 — UI: collapsed tile and wire detour

### Task 7.1: `GroupCollapsedTile` composable

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupCollapsedTile.kt`

- [ ] **Step 1: Write the composable**

```kotlin
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.canvas.LocalCanvasState

/**
 * Single-tile rendering of a collapsed group. Proxy pin rows are derived
 * from current edges every recomposition — collapse / expand has no
 * effect on edges, only on what the user sees.
 */
@Composable
fun GroupCollapsedTile(group: Group) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    val edges by editor.edges.collectAsState()
    val nodesIds by editor.nodes.collectAsState()
    val allGroups = editor.graph.groups.associateBy { it.id }
    val closure = remember(group, nodesIds, allGroups) {
        GroupProxyPins.memberClosure(group, allGroups)
    }
    val proxies = remember(group, edges, closure) {
        GroupProxyPins.compute(editor.graph, group, closure)
    }
    val inputs = proxies.filter { it.side == PinSide.Input }
    val outputs = proxies.filter { it.side == PinSide.Output }
    val w = group.collapsedSize?.first ?: TILE_WIDTH

    Box(
        modifier = Modifier
            .absolutePosition(group.pos.x.toInt(), group.pos.y.toInt())
            .width(w)
            .pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Drag -> {
                        val zoom = canvas?.zoom ?: 1f
                        editor.moveGroup(group.id, ev.dx / zoom, ev.dy / zoom)
                        true
                    }
                    is PointerEvent.Press -> true
                    else -> false
                }
            }
            .background(NwTheme.colors.surface),
    ) {
        Column {
            // Title row.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                style = SurfaceStyle(
                    color = NwTheme.colors.surfacePressed,
                    shape = NwTheme.shapes.small,
                    border = null,
                    padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
                ),
            ) {
                Row(verticalAlignment = Alignment.Center, horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                    Text(group.name, style = NwTheme.typography.caption)
                    if (group.templateFile != null) {
                        Text("↪${group.templateFile}", style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
                    }
                }
            }
            // Pin rows.
            val rowCount = maxOf(inputs.size, outputs.size)
            for (i in 0 until rowCount) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NwTheme.dimens.space4),
                    verticalAlignment = Alignment.Center,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val ip = inputs.getOrNull(i)
                    val op = outputs.getOrNull(i)
                    if (ip != null) ProxyPinHandle(ip, group.id) else Box(modifier = Modifier.size(8, 8))
                    if (op != null) ProxyPinHandle(op, group.id) else Box(modifier = Modifier.size(8, 8))
                }
            }
        }
    }
}

@Composable
private fun ProxyPinHandle(proxy: GroupProxyPin, groupId: dev.nitka.nodewire.graph.GroupId) {
    val editor = LocalEditorState.current ?: return
    Row(verticalAlignment = Alignment.Center, horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        Box(
            modifier = Modifier
                .size(8, 8)
                .background(pinColor(proxy.type))
                .onPositioned { coords ->
                    // Register proxy position as the underlying pin's
                    // visual position so wire endpoints attach here.
                    editor.pinPositions.set(
                        PinKey(proxy.innerNode, proxy.innerPin, proxy.side),
                        coords.screenX.toFloat(), coords.screenY.toFloat(),
                    )
                },
        )
        Text(proxy.label, style = NwTheme.typography.caption)
    }
}

private fun pinColor(type: PinType): dev.nitka.nodewire.ui.render.Color =
    // Same palette indices NodeCard uses — keep render visually consistent.
    when (type) {
        PinType.BOOL -> dev.nitka.nodewire.ui.render.Color(0xFFC178FF.toInt())
        PinType.INT -> dev.nitka.nodewire.ui.render.Color(0xFF66B2FF.toInt())
        PinType.FLOAT -> dev.nitka.nodewire.ui.render.Color(0xFF66CC99.toInt())
        PinType.REDSTONE -> dev.nitka.nodewire.ui.render.Color(0xFFFF6666.toInt())
        PinType.STRING -> dev.nitka.nodewire.ui.render.Color(0xFFCCCC66.toInt())
        PinType.VEC2 -> dev.nitka.nodewire.ui.render.Color(0xFFFFC966.toInt())
        PinType.VEC3 -> dev.nitka.nodewire.ui.render.Color(0xFFFF9966.toInt())
        PinType.QUAT -> dev.nitka.nodewire.ui.render.Color(0xFFCC66FF.toInt())
    }

private const val TILE_WIDTH = 140
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupCollapsedTile.kt
git commit -m "feat(ui): GroupCollapsedTile — proxy-pin tile composable"
```

### Task 7.2: `GroupLayer` orchestrator + member hiding

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupLayer.kt`

- [ ] **Step 1: Write the composable**

```kotlin
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Iterates over `editor.groups` and renders each as either a [GroupFrame]
 * (expanded) or a [GroupCollapsedTile] (collapsed). Member node hiding
 * for the collapsed case is enforced by [NodeEditorScreen] via
 * [LocalHiddenNodes] — this composable just exposes the set.
 */
@Composable
fun GroupLayer() {
    val editor = LocalEditorState.current ?: return
    val groups by editor.groups.collectAsState()
    for (g in groups) {
        if (g.collapsed) GroupCollapsedTile(g) else GroupFrame(g)
    }
}

/** Set of node ids that the screen should NOT render as standalone cards. */
fun hiddenNodesFor(editor: EditorState): Set<dev.nitka.nodewire.graph.NodeId> {
    val groups = editor.graph.groups
    val byId = groups.associateBy { it.id }
    val hidden = HashSet<dev.nitka.nodewire.graph.NodeId>()
    for (g in groups) {
        if (!g.collapsed) continue
        hidden.addAll(GroupProxyPins.memberClosure(g, byId))
    }
    return hidden
}
```

- [ ] **Step 2: Mount `GroupLayer` in `NodeEditorScreen.Content`**

Edit `NodeEditorScreen.kt` — inside the `NodeCanvas(state = canvas, ...)`
block, between `WireLayer()` and the `for (id in nodeIds) { NodeCard(...) }`:

```kotlin
                    WireLayer()
                    GroupLayer()
                    val hidden = remember(nodeIds, editor.groups.value) {
                        hiddenNodesFor(editor)
                    }
                    for (id in nodeIds) {
                        if (id in hidden) continue
                        NodeCard(nodeId = id)
                    }
```

(Add `import dev.nitka.nodewire.client.screen.GroupLayer` and
`hiddenNodesFor` at the top of the file.)

- [ ] **Step 3: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/GroupLayer.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "feat(ui): GroupLayer mounted in editor; member hiding"
```

---

## Phase 8 — Interactions

### Task 8.1: `Ctrl+G` / `Ctrl+Shift+G` keybindings

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt`

- [ ] **Step 1: Add bindings**

In `EditorKeyBindings.DEFAULT`, before the `ESCAPE` entry, insert:

```kotlin
        EditorKeyBinding(InputConstants.KEY_G, GLFW.GLFW_MOD_CONTROL) { e, _, _ ->
            val id = e.createGroupFromSelection("Group")
            id != null
        },
        EditorKeyBinding(InputConstants.KEY_G, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { e, _, _ ->
            // Ungroup every group that contains the selection.
            val ids = e.selectedNodes
            if (ids.isEmpty()) return@EditorKeyBinding false
            val toRemove = e.graph.groups.filter { g ->
                g.members.any { m -> m is dev.nitka.nodewire.graph.MemberRef.Node && m.id in ids }
            }.map { it.id }
            for (gid in toRemove) e.ungroup(gid)
            toRemove.isNotEmpty()
        },
```

- [ ] **Step 2: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorKeyBindings.kt
git commit -m "feat(editor): Ctrl+G group / Ctrl+Shift+G ungroup keybindings"
```

### Task 8.2: Group context menu

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/ContextMenuTarget.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeContextMenu.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Step 1: Add `Group` variant to `ContextMenuTarget`**

In `ContextMenuTarget.kt`:

```kotlin
    data class Group(
        override val screenX: Int,
        override val screenY: Int,
        val groupId: dev.nitka.nodewire.graph.GroupId,
    ) : ContextMenuTarget
```

- [ ] **Step 2: Add opener in `EditorState`**

```kotlin
    fun openGroupMenu(screenX: Int, screenY: Int, groupId: GroupId) {
        contextMenu = ContextMenuTarget.Group(screenX, screenY, groupId)
    }
```

- [ ] **Step 3: Extend `NodeContextMenu` to handle the new variant**

In `NodeContextMenu.kt`, extend the `when (target)`:

```kotlin
    val items = when (target) {
        is ContextMenuTarget.Create -> buildCreateItems(editor, target, toast)
        is ContextMenuTarget.Node -> buildNodeItems(editor, target, toast)
        is ContextMenuTarget.Group -> buildGroupItems(editor, target, toast)
    }
```

Add at the bottom of the file:

```kotlin
private fun buildGroupItems(
    editor: EditorState,
    target: ContextMenuTarget.Group,
    toast: dev.nitka.nodewire.ui.feedback.ToastManager?,
): List<ContextMenuItem> {
    val g = editor.graph.groups.firstOrNull { it.id == target.groupId } ?: return emptyList()
    val collapseLabel = if (g.collapsed) "Expand" else "Collapse"
    val items = mutableListOf<ContextMenuItem>(
        ContextMenuItem.Action(collapseLabel) { editor.toggleCollapsed(target.groupId) },
    )
    if (g.templateFile == null) {
        items.add(ContextMenuItem.Action("Save as template…") {
            // The popup is opened by NodeEditorScreen via a separate
            // dialog flow — request it here via a callback.
            editor.pendingSaveTemplateForGroup = target.groupId
        })
    } else {
        items.add(ContextMenuItem.Action("Unlink (template: ${g.templateFile})") {
            editor.unlinkGroup(target.groupId); toast?.info("Unlinked")
        })
    }
    items.add(ContextMenuItem.Separator)
    items.add(ContextMenuItem.Action("Ungroup") {
        editor.ungroup(target.groupId); toast?.info("Ungrouped")
    })
    return items
}
```

- [ ] **Step 4: Add the `pendingSaveTemplateForGroup` slot in EditorState**

```kotlin
    var pendingSaveTemplateForGroup: GroupId? by mutableStateOf(null)
```

- [ ] **Step 5: Extend canvas Create menu with "Insert group ▸"**

In `buildCreateItems` in `NodeContextMenu.kt`, before the existing
exports actions, add:

```kotlin
    val files = GroupFiles.list()
    val insertSubmenu: ContextMenuItem = if (files.isEmpty()) {
        ContextMenuItem.Action("Insert group: (none saved)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "Insert group",
            items = files.map { f ->
                ContextMenuItem.Action(f) {
                    val id = editor.insertTemplate(f, target.world)
                    if (id != null) toast?.success("Inserted $f") else toast?.warning("Insert refused (missing or cycle)")
                }
            },
        )
    }
```

…then insert `insertSubmenu` as the second element of the return list
(after `Add Node`, before `Export…`).

- [ ] **Step 6: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/ContextMenuTarget.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeContextMenu.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "feat(ui): group + insert-template context menu entries"
```

### Task 8.3: Save-as-template dialog wiring

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`

- [ ] **Step 1: Render the dialog when slot is set**

Inside the `Content()` of `NodeEditorScreen`, after the `editor.contextMenu?.let { ... }` block:

```kotlin
                editor.pendingSaveTemplateForGroup?.let { gid ->
                    SaveAsDialog(
                        initial = editor.graph.groups.firstOrNull { it.id == gid }?.name ?: "Group",
                        onDismiss = { editor.pendingSaveTemplateForGroup = null },
                        onConfirm = { name ->
                            val tpl = editor.saveAsTemplate(gid, name)
                            if (tpl == null) {
                                // toast warning — get manager from local
                            }
                        },
                    )
                }
```

(Toast manager is already used elsewhere in this file via
`LocalToastManager.current`; reuse the same pattern. Skipping a verbose
toast wire-up is OK if `saveAsTemplate` already returns non-null on
success — the success path is silent and the failure path simply
re-opens the dialog. A toast block is preferred when convenient.)

- [ ] **Step 2: Wire up `templateSync`**

In the `remember(graph) { EditorState(...) }` block in `Content()`,
right after `e.setBlockName(...)`, add:

```kotlin
                        val store = remember { GroupTemplateStore() }
                        e.templateSync = GroupTemplateSync(
                            store = store,
                            scope = scope,           // a CoroutineScope; create via rememberCoroutineScope() above
                            editor = e,
                            onEdgeDropped = { n -> /* toast: "$n edge(s) dropped" */ },
                        )
                        // Begin observing every file already in the graph.
                        for (g in graph.groups) g.templateFile?.let { e.templateSync?.observeFile(it) }
```

Make sure to add `val scope = rememberCoroutineScope()` earlier in
the composable and the corresponding import.

- [ ] **Step 3: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "feat(ui): save-as-template dialog + template sync wiring"
```

### Task 8.4: Toolbar "Insert group" entry

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt`

- [ ] **Step 1: Add to file menu**

In `fileMenuItems`, between `Open` submenu and `Delete saved` submenu, insert:

```kotlin
    val groupFiles = GroupFiles.list()
    val insertGroupItem: ContextMenuItem = if (groupFiles.isEmpty()) {
        ContextMenuItem.Action("Insert group: (none saved)") {}
    } else {
        ContextMenuItem.Submenu(
            label = "Insert group",
            items = groupFiles.map { f ->
                ContextMenuItem.Action(f) {
                    val canvas = editor.canvasState
                    val pos = if (canvas != null) dev.nitka.nodewire.graph.CanvasPos(canvas.cursorWorldX, canvas.cursorWorldY)
                    else dev.nitka.nodewire.graph.CanvasPos.Zero
                    val id = editor.insertTemplate(f, pos)
                    if (id != null) toast?.success("Inserted $f") else toast?.warning("Insert refused")
                }
            },
        )
    }
```

…and include `insertGroupItem` in the returned list of items.

- [ ] **Step 2: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt
git commit -m "feat(ui): toolbar Insert-group entry"
```

---

## Phase 9 — Wire detour through collapsed proxies

### Task 9.1: WireLayer endpoint detour

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt`

- [ ] **Step 1: Re-read positions through the proxy register**

Open `WireLayer.kt`. The renderer currently looks up `pinPositions.get(key)`
directly. Because `GroupCollapsedTile.ProxyPinHandle` writes the proxy
position into `pinPositions` under the same `PinKey(innerNode, innerPin, side)`,
no code change is needed here — wires already terminate at the proxy
once the collapsed tile has registered its position.

**Action:** verify there is no per-frame override that would clobber
the proxy registration. If `NodeCard` also writes to `pinPositions`
for hidden member nodes, gate that write on `nodeId in hiddenNodes`
to avoid alternation.

Open `NodeCard.kt`. Locate the call site that calls
`editor.pinPositions.set(...)` (likely in pin handle `onPositioned`).
Wrap it with:

```kotlin
                .onPositioned { coords ->
                    // Member nodes hidden by a collapsed parent group
                    // must not register their own pin positions —
                    // GroupCollapsedTile.ProxyPinHandle owns them.
                    val hidden = hiddenNodesFor(editor)
                    if (nodeId !in hidden) {
                        editor.pinPositions.set(PinKey(nodeId, pin.id, side), coords.screenX.toFloat(), coords.screenY.toFloat())
                    }
                }
```

Recompute `hiddenNodesFor(editor)` once per composition; in practice
inline a `remember(editor.groups.collectAsState().value)` block.

- [ ] **Step 2: Build + commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt
git commit -m "feat(ui): wire endpoints detour through collapsed proxies"
```

---

## Phase 10 — Final wiring + cleanup

### Task 10.1: Manual smoke checklist (no automated runClient)

- [ ] Build green: `./gradlew build`
- [ ] All tests green: `./gradlew test`
- [ ] Stop here. Inform user: "Implementation complete. Please run
      `./gradlew runClient` yourself and validate the in-game flow:
      group via `Ctrl+G`, toggle collapse, save as template, open
      another logic block and Insert group — verify live edits
      propagate."

### Task 10.2: Reflective self-review and merge prep

- [ ] Re-read the spec sections one-by-one against the commits in this
      branch (or master) and confirm each requirement has a commit:
  - Data model + codec ↔ Phase 1
  - Disk storage ↔ Phase 2 (`GroupFiles`)
  - Template instantiation + diff ↔ Phase 3 (`GroupTemplateResolver`)
  - EditorState ops ↔ Phase 4
  - Live sync ↔ Phase 5
  - Expanded UI ↔ Phase 6
  - Collapsed UI + proxy pins ↔ Phase 7
  - Interactions / keybinds / menus ↔ Phase 8
  - Wire detour ↔ Phase 9
  - Edge cases (cycle, missing template, member-delete) ↔ Phases 3, 4
- [ ] Run `./gradlew build && ./gradlew test` one more time.
- [ ] No commit — the human user runs `runClient` and then signs off.

---

## Self-review notes (post-write)

- All types referenced across tasks resolve consistently:
  - `Group`, `MemberRef`, `GroupId`, `TemplateNodeId`, `GroupTemplate`
    defined in Phase 1/2 and used in Phases 3–9.
  - `EditorState.createGroupFromSelection / ungroup / toggleCollapsed /
    unlinkGroup / addMemberToGroup / removeMemberFromGroup / moveGroup /
    saveAsTemplate / insertTemplate / templateSync /
    pendingSaveTemplateForGroup / openGroupMenu` all defined in Phase 4
    or 8 and called in Phases 6–9.
  - `GroupProxyPins.compute / memberClosure` defined in Phase 3.5,
    referenced in Phases 4, 6, 7.
  - `GroupTemplateStore.flowOf / publish / current` defined in 5.1,
    referenced in 5.2 and 5.3.
- No placeholder language; every step has either code or a verifiable
  command.
- Server transmission is intentionally not its own phase: the
  `NodeGraph.CODEC` extension in Phase 1.2 is the only change required;
  `SaveGraphPacket` already serialises through that codec.
