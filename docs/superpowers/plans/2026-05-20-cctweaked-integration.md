# CC: Tweaked Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose Nodewire's named channels to CC: Tweaked computers as a block peripheral, with read/write/event/introspection APIs.

**Architecture:** New `integration/cctweaked/` module with five focused files. ModList-guarded — without CC: Tweaked loaded, none of these classes initialize. Reuses the existing `LogicBlockEntity.externalChannelInputs` map for writes; adds a `lastChannelOutputSnapshot` field for event diffing.

**Tech Stack:** NeoForge 1.21.1, Kotlin 2.0.20, CC: Tweaked 1.115.1 (NeoForge), JUnit 5.

**Branch:** `dev`. No feature branches. Do NOT run `./gradlew runClient` (the user runs it manually). Use `compileKotlin`, `test`, `build`.

**Spec:** [`docs/superpowers/specs/2026-05-20-cctweaked-integration-design.md`](../specs/2026-05-20-cctweaked-integration-design.md)

---

## File layout

| File | Responsibility |
| ---- | -------------- |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodec.kt` | Bidirectional `PinValue ↔ Lua Object` conversion. |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospection.kt` | `inputs(graph)` and `outputs(graph)` maps; `outputSnapshot(graph, EvalResult)`. |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospection.kt` | `nodesLua(graph)` / `edgesLua(graph)` shaping for Lua tables. |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt` | `IPeripheral` impl. Attach/detach, the 5 Lua-callable methods. |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatch.kt` | `diffAndBroadcast(be, prev, new)` per-tick logic. |
| `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwPeripheralCapability.kt` | Constants + `BlockCapability` registration. |
| `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodecTest.kt` | Codec round-trip + error paths. |
| `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospectionTest.kt` | Channel-map shaping. |
| `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospectionTest.kt` | Graph-map shaping. |
| `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatchTest.kt` | Diff + broadcast against a fake `IComputerAccess`. |

Modified existing files:
- `build.gradle.kts` — add CC deps (compileOnly + runtimeOnly + testImplementation).
- `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt` — ModList-gated `NwPeripheralCapability.register(MOD_BUS)` call.
- `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt` — new `lastChannelOutputSnapshot` field + post-eval hook into `NwChannelEventDispatch`.

---

## Phase 1 — Foundation

### Task 1: CC: Tweaked build dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add CC: Tweaked dependencies**

Inside the existing `dependencies { ... }` block, after the `tweakedcontroller` lines, add:

```kotlin
    // CC: Tweaked — peripheral integration (mod gated at runtime; module
    // compiles against the API jar so source references resolve).
    val ccVer = "1.115.1"
    compileOnly("cc.tweaked:cc-tweaked-${mcVer}-neoforge-api:$ccVer")
    runtimeOnly("cc.tweaked:cc-tweaked-${mcVer}-neoforge:$ccVer")
    testImplementation("cc.tweaked:cc-tweaked-${mcVer}-neoforge-api:$ccVer")
```

- [ ] **Step 2: Run `./gradlew dependencies` to verify resolution**

Run: `./gradlew dependencies --configuration compileClasspath -q | grep -i cc-tweaked`

Expected: lines containing `cc-tweaked-1.21.1-neoforge-api:1.115.1` (with a `(*)` marker is fine).

If the version doesn't resolve, change `1.115.1` to the latest 1.21.1-compatible release (check https://modrinth.com/mod/cc-tweaked/versions filtered to MC 1.21.1 + NeoForge).

- [ ] **Step 3: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`. No references yet, so the deps just sit on the classpath.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build(cc): add CC: Tweaked 1.115.1 dependencies"
```

---

### Task 2: PinValue ↔ Lua codec

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodec.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodecTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodecTest.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaException
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NwChannelLuaCodecTest {

    @Test fun `bool round trips`() {
        assertEquals(true, NwChannelLuaCodec.toLua(PinValue.Bool(true)))
        assertEquals(PinValue.Bool(true), NwChannelLuaCodec.fromLua(true, PinType.BOOL))
    }

    @Test fun `int floors fractional numbers`() {
        assertEquals(PinValue.Int(3), NwChannelLuaCodec.fromLua(3.7, PinType.INT))
    }

    @Test fun `redstone clamps to 0_15`() {
        assertEquals(PinValue.Redstone(15), NwChannelLuaCodec.fromLua(99.0, PinType.REDSTONE))
        assertEquals(PinValue.Redstone(0), NwChannelLuaCodec.fromLua(-5.0, PinType.REDSTONE))
    }

    @Test fun `vec3 requires x y z`() {
        val v = NwChannelLuaCodec.fromLua(mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0), PinType.VEC3)
        assertEquals(PinValue.Vec3(1f, 2f, 3f), v)
    }

    @Test fun `vec3 missing field throws`() {
        val ex = assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua(mapOf("x" to 1.0, "y" to 2.0), PinType.VEC3)
        }
        assert(ex.message!!.contains("type mismatch")) { "got: ${ex.message}" }
    }

    @Test fun `wrong primitive class throws`() {
        assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua("hello", PinType.INT)
        }
    }

    @Test fun `nan rejected`() {
        assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua(Double.NaN, PinType.FLOAT)
        }
    }

    @Test fun `quat round trip`() {
        val src = PinValue.Quat(0.1f, 0.2f, 0.3f, 0.9f)
        val lua = NwChannelLuaCodec.toLua(src) as Map<*, *>
        assertEquals(0.1f.toDouble(), (lua["x"] as Number).toDouble(), 1e-6)
        val back = NwChannelLuaCodec.fromLua(lua, PinType.QUAT) as PinValue.Quat
        assertEquals(0.9f, back.w, 1e-6f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelLuaCodecTest"`

Expected: compilation failure — `Unresolved reference: NwChannelLuaCodec`.

- [ ] **Step 3: Write the codec**

Create `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodec.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaException
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue

/**
 * Convert [PinValue] to a Lua-safe Java Object and back. Throws
 * [LuaException] on decode of an incompatible Lua value — message starts
 * with "type mismatch: expected <type>" so Lua callers see a clear cause.
 */
object NwChannelLuaCodec {

    fun toLua(value: PinValue): Any = when (value) {
        is PinValue.Bool     -> value.value
        is PinValue.Int      -> value.value.toLong()
        is PinValue.Redstone -> value.value.toLong()
        is PinValue.Float    -> value.value.toDouble()
        is PinValue.Str      -> value.value
        is PinValue.Vec2     -> mapOf("x" to value.x.toDouble(), "y" to value.y.toDouble())
        is PinValue.Vec3     -> mapOf(
            "x" to value.x.toDouble(),
            "y" to value.y.toDouble(),
            "z" to value.z.toDouble(),
        )
        is PinValue.Quat     -> mapOf(
            "x" to value.x.toDouble(),
            "y" to value.y.toDouble(),
            "z" to value.z.toDouble(),
            "w" to value.w.toDouble(),
        )
    }

    fun fromLua(lua: Any?, type: PinType): PinValue = when (type) {
        PinType.BOOL     -> PinValue.Bool(asBool(lua, "bool"))
        PinType.INT      -> PinValue.Int(asFiniteNumber(lua, "int").toInt())
        PinType.FLOAT    -> PinValue.Float(asFiniteNumber(lua, "float").toFloat())
        PinType.REDSTONE -> PinValue.Redstone(
            asFiniteNumber(lua, "redstone").toInt().coerceIn(0, 15)
        )
        PinType.STRING   -> PinValue.Str(asString(lua, "string"))
        PinType.VEC2     -> {
            val m = asMap(lua, "vec2")
            PinValue.Vec2(
                asFiniteNumber(m["x"], "vec2").toFloat(),
                asFiniteNumber(m["y"], "vec2").toFloat(),
            )
        }
        PinType.VEC3     -> {
            val m = asMap(lua, "vec3")
            PinValue.Vec3(
                asFiniteNumber(m["x"], "vec3").toFloat(),
                asFiniteNumber(m["y"], "vec3").toFloat(),
                asFiniteNumber(m["z"], "vec3").toFloat(),
            )
        }
        PinType.QUAT     -> {
            val m = asMap(lua, "quat")
            PinValue.Quat(
                asFiniteNumber(m["x"], "quat").toFloat(),
                asFiniteNumber(m["y"], "quat").toFloat(),
                asFiniteNumber(m["z"], "quat").toFloat(),
                asFiniteNumber(m["w"], "quat").toFloat(),
            )
        }
    }

    private fun asBool(v: Any?, label: String): Boolean =
        v as? Boolean ?: throw LuaException("type mismatch: expected $label")

    private fun asString(v: Any?, label: String): String =
        v as? String ?: throw LuaException("type mismatch: expected $label")

    private fun asMap(v: Any?, label: String): Map<*, *> =
        v as? Map<*, *> ?: throw LuaException("type mismatch: expected $label")

    private fun asFiniteNumber(v: Any?, label: String): Double {
        val d = (v as? Number)?.toDouble()
            ?: throw LuaException("type mismatch: expected $label")
        if (d.isNaN() || d.isInfinite()) throw LuaException("type mismatch: expected $label")
        return d
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelLuaCodecTest"`

Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodec.kt \
        src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelLuaCodecTest.kt
git commit -m "feat(cc): NwChannelLuaCodec — PinValue <-> Lua bridge"
```

---

## Phase 2 — Introspection

### Task 3: Channel-map introspection

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospection.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospectionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospectionTest.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NwChannelIntrospectionTest {

    private fun channelNode(typePath: String, name: String, type: PinType): Node {
        val cfg = CompoundTag().apply {
            putString("name", name)
            putString("type", type.name)
        }
        val pin = Pin("io", "Value", type)
        return Node(
            id = Node.newId(),
            typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", typePath),
            pos = CanvasPos(0f, 0f),
            inputs = if (typePath == "channel_output") listOf(pin) else emptyList(),
            outputs = if (typePath == "channel_input") listOf(pin) else emptyList(),
            config = cfg,
        )
    }

    @Test fun `collects named channel_input nodes`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "speed", PinType.FLOAT))
        g.add(channelNode("channel_input", "enable", PinType.BOOL))
        assertEquals(
            mapOf("speed" to PinType.FLOAT, "enable" to PinType.BOOL),
            NwChannelIntrospection.inputs(g),
        )
    }

    @Test fun `skips blank names`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "", PinType.BOOL))
        g.add(channelNode("channel_input", "ok", PinType.BOOL))
        assertEquals(mapOf("ok" to PinType.BOOL), NwChannelIntrospection.inputs(g))
    }

    @Test fun `first duplicate wins`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "x", PinType.FLOAT))
        g.add(channelNode("channel_input", "x", PinType.BOOL))
        assertEquals(mapOf("x" to PinType.FLOAT), NwChannelIntrospection.inputs(g))
    }

    @Test fun `outputs filtered separately`() {
        val g = NodeGraph()
        g.add(channelNode("channel_input", "in", PinType.BOOL))
        g.add(channelNode("channel_output", "out", PinType.INT))
        assertEquals(mapOf("in" to PinType.BOOL), NwChannelIntrospection.inputs(g))
        assertEquals(mapOf("out" to PinType.INT), NwChannelIntrospection.outputs(g))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelIntrospectionTest"`

Expected: `Unresolved reference: NwChannelIntrospection`.

- [ ] **Step 3: Write the introspection module**

Create `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospection.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.EvalResult
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue

/**
 * Maps of channel names exposed by a graph. Inputs are written FROM CC
 * into the graph; outputs are read FROM the graph by CC.
 *
 * Duplicates: first-by-creation-order wins. Blank names are skipped
 * silently — the editor permits empty names while the user is mid-edit.
 */
object NwChannelIntrospection {

    fun inputs(graph: NodeGraph): Map<String, PinType> =
        collect(graph, "channel_input")

    fun outputs(graph: NodeGraph): Map<String, PinType> =
        collect(graph, "channel_output")

    /**
     * Per-name current value of each `channel_output` node, taken from
     * the value flowing into its "in" pin in [result]. Channels with no
     * incoming edge are absent from the result (so CC sees `nil`).
     */
    fun outputSnapshot(graph: NodeGraph, result: EvalResult): Map<String, PinValue> {
        val out = LinkedHashMap<String, PinValue>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "channel_output") continue
            val name = node.config.getString("name").ifBlank { continue }
            if (name in out) continue
            val edge = graph.edges.firstOrNull {
                it.to.node == node.id && it.to.pin == "in"
            } ?: continue
            val value = result.valueAt(edge.from.node, edge.from.pin) ?: continue
            out[name] = value
        }
        return out
    }

    private fun collect(graph: NodeGraph, typePath: String): Map<String, PinType> {
        val out = LinkedHashMap<String, PinType>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != typePath) continue
            val name = node.config.getString("name").ifBlank { continue }
            if (name in out) continue
            val type = PinType.fromName(node.config.getString("type"))
            out[name] = type
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelIntrospectionTest"`

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospection.kt \
        src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelIntrospectionTest.kt
git commit -m "feat(cc): NwChannelIntrospection — graph channel maps"
```

---

### Task 4: Graph introspection (nodes + edges as Lua tables)

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospection.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospectionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospectionTest.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NwGraphIntrospectionTest {

    private fun bareNode(id: java.util.UUID = Node.newId()): Node = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "channel_input"),
        pos = CanvasPos(0f, 0f),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        config = CompoundTag(),
    )

    @Test fun `nodesLua emits one map per node`() {
        val g = NodeGraph()
        val n = bareNode()
        g.add(n)
        val list = NwGraphIntrospection.nodesLua(g)
        assertEquals(1, list.size)
        val m = list[0]
        assertEquals(n.id.toString(), m["id"])
        assertEquals("nodewire:channel_input", m["type"])
        val outputs = m["outputs"] as List<*>
        assertEquals(1, outputs.size)
        val outPin = outputs[0] as Map<*, *>
        assertEquals("out", outPin["id"])
        assertEquals("bool", outPin["type"])
    }

    @Test fun `edgesLua emits from to label`() {
        val g = NodeGraph()
        val a = bareNode()
        val b = bareNode()
        g.add(a); g.add(b)
        g.addEdge(Edge(
            id = Edge.newId(),
            from = PinRef(a.id, "out"),
            to = PinRef(b.id, "out"),
            label = "wire-A",
        ))
        val list = NwGraphIntrospection.edgesLua(g)
        assertEquals(1, list.size)
        val e = list[0]
        val from = e["from"] as Map<*, *>
        assertEquals(a.id.toString(), from["node"])
        assertEquals("out", from["pin"])
        assertEquals("wire-A", e["label"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwGraphIntrospectionTest"`

Expected: `Unresolved reference: NwGraphIntrospection`.

- [ ] **Step 3: Write the introspection module**

Create `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospection.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin

/**
 * Shape `nodes` and `edges` of a [NodeGraph] into Lua-safe (UUID-as-string,
 * primitive-typed) maps. CC: Tweaked converts a `Map<String, Object>` into
 * a Lua table on return, but UUIDs aren't recognised — caller must
 * stringify identifiers.
 */
object NwGraphIntrospection {

    fun nodesLua(graph: NodeGraph): List<Map<String, Any?>> =
        graph.nodes.values.map { n ->
            mapOf(
                "id" to n.id.toString(),
                "type" to n.typeKey.toString(),
                "label" to n.label?.takeIf { it.isNotBlank() },
                "inputs" to n.inputs.map(::pinLua),
                "outputs" to n.outputs.map(::pinLua),
            )
        }

    fun edgesLua(graph: NodeGraph): List<Map<String, Any?>> =
        graph.edges.map { e ->
            mapOf(
                "from" to mapOf(
                    "node" to e.from.node.toString(),
                    "pin" to e.from.pin,
                ),
                "to" to mapOf(
                    "node" to e.to.node.toString(),
                    "pin" to e.to.pin,
                ),
                "label" to e.label?.takeIf { it.isNotBlank() },
            )
        }

    private fun pinLua(pin: Pin): Map<String, Any?> = mapOf(
        "id" to pin.id,
        "name" to pin.name,
        "type" to pin.type.name.lowercase(),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwGraphIntrospectionTest"`

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

If `Node.label` doesn't exist (it was added during the rename overlays work), check `git log -- src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` — the field is called `label: String?`. If the field name differs, update the code to match. If labels are not on Node, drop the `"label"` key in the map.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospection.kt \
        src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwGraphIntrospectionTest.kt
git commit -m "feat(cc): NwGraphIntrospection — node/edge maps for Lua"
```

---

## Phase 3 — Peripheral

### Task 5: NodewirePeripheral skeleton

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt`

- [ ] **Step 1: Write the skeleton**

Create the file with just the lifecycle / identity methods. The 5 Lua-callable methods will be added in Tasks 6 and 7.

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.Direction
import java.util.WeakHashMap

/**
 * `IPeripheral` exposed by every [LogicBlockEntity] face. CC: Tweaked
 * calls the `@LuaFunction`-annotated methods. Equality is `(be, side)`
 * identity so CC's peripheral cache works correctly.
 *
 * Attached computers are tracked in a [WeakHashMap] so a missed
 * `detach` doesn't leak a `IComputerAccess` reference. Per-attach
 * `needsInitialSync` flag is set on attach and cleared by the first
 * dispatch — the BE consults it when building the prev/new snapshot.
 */
class NodewirePeripheral(
    val be: LogicBlockEntity,
    val side: Direction,
) : IPeripheral {

    /** Map<computer, needsInitialSync>. Read under `attached.synchronized`. */
    internal val attached: MutableMap<IComputerAccess, Boolean> = WeakHashMap()

    override fun getType(): String = "nodewire"

    override fun equals(other: IPeripheral?): Boolean =
        other is NodewirePeripheral && other.be === be && other.side == side

    override fun attach(computer: IComputerAccess) {
        synchronized(attached) { attached[computer] = true }
        // Register this peripheral with the BE so server-tick can fire
        // events. BE keeps a Set<NodewirePeripheral>; see Task 9.
        be.nwAttachPeripheral(this)
    }

    override fun detach(computer: IComputerAccess) {
        synchronized(attached) { attached.remove(computer) }
        if (synchronized(attached) { attached.isEmpty() }) {
            be.nwDetachPeripheral(this)
        }
    }

    /** Snapshot the current attachment set for safe iteration during dispatch. */
    internal fun attachmentsSnapshot(): List<Pair<IComputerAccess, Boolean>> =
        synchronized(attached) { attached.entries.map { it.key to it.value } }

    internal fun clearInitialSync(computer: IComputerAccess) {
        synchronized(attached) { attached[computer] = false }
    }
}
```

- [ ] **Step 2: Add stub BE hooks so the file compiles**

Modify `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt` — add two no-op functions near the bottom of the class, before the closing brace. They'll be wired up in Task 9.

```kotlin
    // ── CC: Tweaked attached-peripheral tracking ──────────────────────────
    // Set is mutated only from the server thread (CC dispatches on its
    // own executor but always trampolines back via Capabilities), so a
    // plain MutableSet is fine.
    @Transient
    private val nwAttachedPeripherals: MutableSet<Any> = HashSet()

    /** Called from `NodewirePeripheral.attach`. Idempotent. */
    fun nwAttachPeripheral(p: Any) { nwAttachedPeripherals.add(p) }

    /** Called from `NodewirePeripheral.detach` when the last computer leaves. */
    fun nwDetachPeripheral(p: Any) { nwAttachedPeripherals.remove(p) }

    /** Read-only view used by [serverTick]'s CC dispatch step. */
    internal fun nwAttachedPeripheralsView(): Set<Any> = nwAttachedPeripherals
```

The peripherals are stored as `Any` to keep the BE free of a hard reference to the CC API class — that way the BE class loads cleanly without CC: Tweaked on the classpath.

- [ ] **Step 3: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt \
        src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "feat(cc): NodewirePeripheral skeleton + BE attach hooks"
```

---

### Task 6: getChannel + setChannel

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt`

- [ ] **Step 1: Add the two Lua-callable methods**

Insert below `clearInitialSync` (above the closing brace) in `NodewirePeripheral.kt`:

```kotlin
    /**
     * Read the current value of a `channel_output` node by name.
     * Returns `nil` (Java null) if no such named channel exists. The
     * value comes from the cached snapshot maintained by serverTick —
     * see [LogicBlockEntity.nwChannelOutputSnapshotView].
     */
    @LuaFunction
    fun getChannel(name: String): Any? {
        val snap = be.nwChannelOutputSnapshotView()
        val v = snap[name] ?: return null
        return NwChannelLuaCodec.toLua(v)
    }

    /**
     * Write a value to a `channel_input` node by name. Type-checks
     * the Lua value against the channel's declared `PinType`; throws
     * `LuaException("no such channel")` or `"type mismatch: expected X"`
     * with the channel's type name. The new value is picked up by the
     * next server tick via `LogicBlockEntity.externalChannelInputs`.
     */
    @LuaFunction
    fun setChannel(name: String, value: Any?): Boolean {
        val inputs = NwChannelIntrospection.inputs(be.graph)
        val type = inputs[name]
            ?: throw dan200.computercraft.api.lua.LuaException("no such channel")
        val pv = NwChannelLuaCodec.fromLua(value, type)
        be.nwWriteChannelInput(name, pv)
        return true
    }
```

- [ ] **Step 2: Add the BE counterpart APIs**

In `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`, after `nwAttachedPeripheralsView`, add:

```kotlin
    /**
     * Last-tick snapshot of `channel_output` values, keyed by channel
     * name. Updated end-of-tick by the CC integration; consulted from
     * Lua [NodewirePeripheral.getChannel].
     */
    @Transient
    private var nwChannelOutputSnapshot: Map<String, dev.nitka.nodewire.graph.PinValue> = emptyMap()

    internal fun nwChannelOutputSnapshotView(): Map<String, dev.nitka.nodewire.graph.PinValue> =
        nwChannelOutputSnapshot

    internal fun nwUpdateChannelOutputSnapshot(snap: Map<String, dev.nitka.nodewire.graph.PinValue>) {
        nwChannelOutputSnapshot = snap
    }

    /**
     * Write to a `channel_input`-fed external. Same map the cross-block
     * channel bindings use, so a Lua writer and another BE writer
     * compete on a last-writer-wins basis (which is the existing
     * channel-binding behaviour).
     */
    internal fun nwWriteChannelInput(name: String, value: dev.nitka.nodewire.graph.PinValue) {
        externalChannelInputs[name] = value
        setChanged()
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt \
        src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "feat(cc): getChannel + setChannel on NodewirePeripheral"
```

---

### Task 7: listChannels + getNodes + getEdges

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt`

- [ ] **Step 1: Add the three introspection methods**

Add below `setChannel` in `NodewirePeripheral.kt`:

```kotlin
    /**
     * { inputs = {name=type, ...}, outputs = {name=type, ...} } where
     * `type` is the lowercased PinType name ("bool", "vec3", …). Blank-
     * named channels are excluded; duplicates → first wins.
     */
    @LuaFunction
    fun listChannels(): Map<String, Any> {
        val inputs = NwChannelIntrospection.inputs(be.graph)
            .mapValues { (_, t) -> t.name.lowercase() }
        val outputs = NwChannelIntrospection.outputs(be.graph)
            .mapValues { (_, t) -> t.name.lowercase() }
        return mapOf("inputs" to inputs, "outputs" to outputs)
    }

    /** Array of nodes with `{ id, type, label, inputs, outputs }`. */
    @LuaFunction
    fun getNodes(): List<Map<String, Any?>> =
        NwGraphIntrospection.nodesLua(be.graph)

    /** Array of edges with `{ from, to, label }`. */
    @LuaFunction
    fun getEdges(): List<Map<String, Any?>> =
        NwGraphIntrospection.edgesLua(be.graph)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NodewirePeripheral.kt
git commit -m "feat(cc): listChannels + getNodes + getEdges Lua methods"
```

---

## Phase 4 — Wiring

### Task 8: Event diff + dispatch

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatch.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatchTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatchTest.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.IComputerAccess
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NwChannelEventDispatchTest {

    private class FakeComputer : IComputerAccess {
        val events = mutableListOf<Triple<String, Any?, Any?>>()

        override fun queueEvent(event: String, vararg args: Any?) {
            // args[0] = channel name, args[1] = value
            events += Triple(event, args.getOrNull(0), args.getOrNull(1))
        }

        // Unused methods — return defaults / throw if unexpectedly called.
        override fun getAttachmentName(): String = "test"
        override fun getAvailablePeripherals(): MutableSet<String> = mutableSetOf()
        override fun getPeripheral(name: String) = null
        override fun mount(desiredLoc: String, contents: dan200.computercraft.api.filesystem.Mount): String? = null
        override fun mount(desiredLoc: String, contents: dan200.computercraft.api.filesystem.Mount, driveName: String?): String? = null
        override fun mountWritable(desiredLoc: String, contents: dan200.computercraft.api.filesystem.WritableMount): String? = null
        override fun mountWritable(desiredLoc: String, contents: dan200.computercraft.api.filesystem.WritableMount, driveName: String?): String? = null
        override fun unmount(location: String?) {}
        override fun getID(): Int = 0
        override fun getMainThreadMonitor(): dan200.computercraft.api.lua.IWorkMonitor =
            throw UnsupportedOperationException()
    }

    @Test fun `identical snapshots emit nothing`() {
        val c = FakeComputer()
        val snap = mapOf("speed" to PinValue.Float(1f))
        NwChannelEventDispatch.diffAndBroadcast(listOf(c to false), prev = snap, new = snap)
        assertTrue(c.events.isEmpty(), "expected no events, got ${c.events}")
    }

    @Test fun `changed value emits one event`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to false),
            prev = mapOf("speed" to PinValue.Float(1f)),
            new = mapOf("speed" to PinValue.Float(2f)),
        )
        assertEquals(1, c.events.size)
        val (ev, name, value) = c.events[0]
        assertEquals("nodewire_channel", ev)
        assertEquals("speed", name)
        assertEquals(2.0, value)
    }

    @Test fun `removed channel emits nil value`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to false),
            prev = mapOf("gone" to PinValue.Bool(true)),
            new = emptyMap(),
        )
        assertEquals(1, c.events.size)
        assertEquals("gone", c.events[0].second)
        assertEquals(null, c.events[0].third)
    }

    @Test fun `needsInitialSync emits all new channels`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to true),
            prev = mapOf("a" to PinValue.Bool(true)), // ignored when needsInitialSync
            new = mapOf("a" to PinValue.Bool(true), "b" to PinValue.Int(7)),
        )
        assertEquals(2, c.events.size)
        val names = c.events.map { it.second }.toSet()
        assertEquals(setOf("a", "b"), names)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelEventDispatchTest"`

Expected: `Unresolved reference: NwChannelEventDispatch`.

- [ ] **Step 3: Write the dispatch module**

Create `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatch.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.IComputerAccess
import dev.nitka.nodewire.graph.PinValue

/**
 * Compare two snapshots of `channel_output` values and queue a
 * `nodewire_channel` event on each attached computer for every changed
 * or removed channel. A computer flagged `needsInitialSync=true` gets
 * an event for every channel in [new] regardless of [prev].
 */
object NwChannelEventDispatch {

    fun diffAndBroadcast(
        attachments: List<Pair<IComputerAccess, Boolean>>,
        prev: Map<String, PinValue>,
        new: Map<String, PinValue>,
    ) {
        if (attachments.isEmpty()) return

        // Channels present in `new` — emit when value differs OR when
        // initial sync is requested.
        for ((name, value) in new) {
            val before = prev[name]
            val differs = before == null || before != value
            for ((computer, needsInitial) in attachments) {
                if (needsInitial || differs) {
                    computer.queueEvent("nodewire_channel", name, NwChannelLuaCodec.toLua(value))
                }
            }
        }
        // Channels present in `prev` but not `new` — emit with nil.
        // Skipped during initial sync (a freshly-attached computer
        // shouldn't see channels that were never there).
        for (name in prev.keys) {
            if (name in new) continue
            for ((computer, needsInitial) in attachments) {
                if (needsInitial) continue
                computer.queueEvent("nodewire_channel", name, null)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.integration.cctweaked.NwChannelEventDispatchTest"`

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatch.kt \
        src/test/kotlin/dev/nitka/nodewire/integration/cctweaked/NwChannelEventDispatchTest.kt
git commit -m "feat(cc): NwChannelEventDispatch — snapshot diff + queueEvent"
```

---

### Task 9: Wire dispatch into serverTick

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

- [ ] **Step 1: Add the post-eval CC hook**

Inside `serverTick`, after the existing `val result = try { ... } finally { ... }` block (line ~449 — locate by searching for `eval.tick(external)` and finding its enclosing finally), insert the new snapshot + diff block. Place it BEFORE the `if (ModList.get().isLoaded("create"))` line:

```kotlin
        // CC: Tweaked event dispatch — skip everything if the mod isn't
        // present so the BE class loads cleanly on CC-less servers.
        if (net.neoforged.fml.ModList.get().isLoaded("computercraft") &&
            nwAttachedPeripheralsView().isNotEmpty()) {
            val newSnap = dev.nitka.nodewire.integration.cctweaked
                .NwChannelIntrospection.outputSnapshot(graph, result)
            // Build the attachments list as Lua-typed pairs. We hop
            // through `Any` in the BE field to keep CC API types out of
            // the BE compile scope; cast back here behind the ModList
            // gate where it's safe.
            val attachments = nwAttachedPeripheralsView().flatMap { p ->
                val peripheral = p as dev.nitka.nodewire.integration.cctweaked.NodewirePeripheral
                peripheral.attachmentsSnapshot()
            }
            dev.nitka.nodewire.integration.cctweaked.NwChannelEventDispatch
                .diffAndBroadcast(attachments, prev = nwChannelOutputSnapshotView(), new = newSnap)
            // Clear all per-attach initial-sync flags now that they've
            // fired at least once.
            for (p in nwAttachedPeripheralsView()) {
                val peripheral = p as dev.nitka.nodewire.integration.cctweaked.NodewirePeripheral
                for ((computer, _) in peripheral.attachmentsSnapshot()) {
                    peripheral.clearInitialSync(computer)
                }
            }
            nwUpdateChannelOutputSnapshot(newSnap)
        }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full test suite to confirm nothing regressed**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`. All existing tests + the four CC tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "feat(cc): LogicBlockEntity.serverTick — CC event dispatch hook"
```

---

### Task 10: Capability registration + Nodewire.init hook

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwPeripheralCapability.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`

- [ ] **Step 1: Write the capability registration**

Create `src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwPeripheralCapability.kt`:

```kotlin
package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.PeripheralCapability
import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.LogicBlockEntity
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent

/**
 * Registers the [NodewirePeripheral] block capability so wired-modem or
 * adjacency lookups by CC: Tweaked resolve to a fresh instance per
 * `(BE, side)`. CC's caching collapses duplicates via [NodewirePeripheral.equals].
 *
 * Safe to call only when CC: Tweaked is loaded. The caller in
 * [dev.nitka.nodewire.Nodewire.init] gates this with [net.neoforged.fml.ModList].
 */
object NwPeripheralCapability {

    fun register(modBus: IEventBus) {
        modBus.addListener<RegisterCapabilitiesEvent> { event ->
            event.registerBlockEntity(
                PeripheralCapability.get(),
                Registry.LOGIC_BLOCK_BE.get(),
            ) { be: LogicBlockEntity, side ->
                if (side == null) null else NodewirePeripheral(be, side)
            }
        }
    }
}
```

If `PeripheralCapability.get()` isn't the correct symbol in the resolved CC version, replace it with the actual `BlockCapability<IPeripheral, Direction>` constant (CC docs / API jar: look in `dan200.computercraft.api.peripheral.PeripheralCapability` or `Capabilities.Peripheral.BLOCK`). The rest of the wiring is unchanged.

- [ ] **Step 2: Add the gated init call**

Modify `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`. Locate the `EndpointBackends.register(WorldBackend)` line (~33) and add immediately after it:

```kotlin
        if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            dev.nitka.nodewire.integration.cctweaked.NwPeripheralCapability.register(MOD_BUS)
        }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full build (no test re-run needed since no test affects this path)**

Run: `./gradlew build -x test`

Expected: `BUILD SUCCESSFUL`. The jar should bundle the new classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/cctweaked/NwPeripheralCapability.kt \
        src/main/kotlin/dev/nitka/nodewire/Nodewire.kt
git commit -m "feat(cc): register peripheral capability on MOD_BUS (ModList-gated)"
```

---

### Task 11: Manual in-client verification

**Files:** none — pure runtime smoke test. Do not dispatch a subagent for this task; stop and ask the user to run the client themselves.

- [ ] **Step 1: User launches the client manually**

Tell the user:

> Зкомпілюй з останнім commit'ом і запусти `./gradlew runClient`. Перевір CC: Tweaked інтеграцію — приклад нижче.

- [ ] **Step 2: User-side verification checklist**

The user should:

1. Place a Nodewire block and edit its graph: add a `Channel Input` named `enable` (type `Bool`), a `Channel Output` named `speed` (type `Float`) wired to a `Constant` of `1.5`.
2. Place a CC: Tweaked computer adjacent to the Nodewire block (or via a wired modem).
3. From the computer, run:

```lua
local nw = peripheral.find("nodewire")
print(nw.getChannel("speed"))     -- expect 1.5
print(nw.listChannels().inputs.enable)  -- expect "bool"
nw.setChannel("enable", true)
print(#nw.getNodes())             -- expect node count
```

4. Subscribe to events:

```lua
while true do
  local ev, name, value = os.pullEvent("nodewire_channel")
  print(name, value)
end
```

Then change the `Constant` value in the in-world editor → CC should print `speed <new>`.

5. Verify the mod also loads without CC: Tweaked — disable the CC mod, launch the client, place a Nodewire block, ensure the editor opens and graphs evaluate normally. No `ClassNotFoundException` in `logs/latest.log`.

- [ ] **Step 3: User reports results**

If anything fails (peripheral not detected, wrong type, missing events, crash log), fix the underlying task and re-test. Mark this task complete only when all five checks pass.

---

## Self-review checklist (filled in by writing-plans skill, kept for the engineer's reference)

**Spec coverage:**
- §Architecture > Module layout → Tasks 2, 3, 4, 5, 8, 10 (one file each).
- §Architecture > Hook points → Tasks 5 (BE attach hooks), 6 (BE channel R/W APIs), 9 (serverTick hook), 10 (Nodewire.init).
- §Architecture > Capability → Task 10.
- §Architecture > Attach lifecycle → Task 5.
- §Lua API surface — Reads → Tasks 6 (`getChannel`), 7 (`listChannels`, `getNodes`, `getEdges`).
- §Lua API surface — Writes → Task 6 (`setChannel`).
- §Lua API surface — Events → Tasks 8 (dispatch), 9 (wiring), 5 (per-attach `needsInitialSync`).
- §Value codec → Task 2.
- §Channel introspection → Task 3.
- §Event dispatch → Task 8.
- §ModList guard → Tasks 9 (BE), 10 (init), 5 (BE field uses `Any` to avoid hard CC reference).
- §Build → Task 1.
- §Testing → Tasks 2, 3, 4 (introspection), 8 (dispatch). Manual = Task 11.

**Type consistency:**
- `NodewirePeripheral.attachmentsSnapshot()` returns `List<Pair<IComputerAccess, Boolean>>` — consumed unchanged by `NwChannelEventDispatch.diffAndBroadcast`.
- `LogicBlockEntity.nwAttachedPeripheralsView()` returns `Set<Any>` — cast back to `NodewirePeripheral` only behind the ModList gate in Task 9.
- `NwChannelIntrospection.outputSnapshot(graph, result)` returns `Map<String, PinValue>` — matches `LogicBlockEntity.nwChannelOutputSnapshot`.
