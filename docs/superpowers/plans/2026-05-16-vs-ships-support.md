# VS Ships Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support LogicBlocks on Valkyrien Skies ships (world↔world, world↔ship, ship↔ship channel bindings) via a pluggable `EndpointBackend` registry that future Create contraption support will plug into without core edits.

**Architecture:** Replace raw `BlockPos` in `ChannelBinding`/`SideBinding`/packets/wire renderer with an `EndpointRef(backendId, payload)` tagged-dispatch type. `EndpointBackend` is an open registry; two built-ins: `nodewire:world` (always) and `nodewire:vs_ship` (gated on `ModList.isLoaded("valkyrienskies")`). Wire renderer transforms each endpoint's center through the backend per frame. Legacy NBT (raw `BlockPos`) migrates automatically via `Codec.either`.

**Tech Stack:** Kotlin 2.0.20, Forge 1.20.1, Valkyrien Skies 2.4.10 (`modImplementation`, direct imports, no reflection), DataFixerUpper Codecs, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-16-vs-ships-support-design.md`

---

## File Structure

**New files:**
- `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointPayload.kt` — interface; carries `blockPos: BlockPos` (Level-routable: world coord or ship-local).
- `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackend.kt` — interface with `id`, `payloadCodec`, `resolveBlockEntity`, `worldCenter`, `claims`.
- `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackends.kt` — `object` registry.
- `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointRef.kt` — `data class` + `ENDPOINT_REF_CODEC` + `from(level, pos)` helper + `resolve`/`worldCenter`.
- `src/main/kotlin/dev/nitka/nodewire/endpoint/WorldBackend.kt` — built-in world backend.
- `src/main/kotlin/dev/nitka/nodewire/integration/vs/VsShipBackend.kt` — VS backend (direct VS imports).
- `src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointRefCodecTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/block/ChannelBindingCodecTest.kt`

**Modified:**
- `block/ChannelBinding.kt`, `block/SideBinding.kt` — `targetPos: BlockPos` → `target: EndpointRef`; `Codec.either(legacyCodec, newCodec)`.
- `block/LogicBlockEntity.kt` — all `targetPos` reads go through `binding.target.payload.blockPos` for Level API, `binding.target.resolve(level)` for BE lookup.
- `block/SideBindingCodecTest.kt` — extend with EndpointRef cases + legacy migration.
- `net/BindChannelPacket.kt`, `net/BindSideChannelPacket.kt`, `net/RemoveBindingPacket.kt`, `net/HighlightPacket.kt` — packet payload migration.
- `client/wire/WireWorldRenderer.kt` — endpoint world-center per frame.
- `item/ChannelLinkToolItem.kt` — VS-aware raycast + `EndpointRef.from` on hit.
- `command/HighlightServerCommand.kt` — payload migration.
- `client/screen/BindingsManagerScreen.kt` — chat-link writers use `EndpointRef`.
- `Nodewire.kt` — register `WorldBackend`; conditional `VsShipBackend.register()`.

---

## Phase 1 — Core abstractions (no behavior change)

### Task 1: `EndpointPayload`, `EndpointBackend`, `EndpointBackends` registry

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointPayload.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackend.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackends.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndpointBackendsTest {
    private object FakePayload : EndpointPayload { override val blockPos: BlockPos = BlockPos.ZERO }

    private class FakeBackend(override val id: ResourceLocation) : EndpointBackend {
        override val payloadCodec: Codec<out EndpointPayload> = Codec.unit(FakePayload)
        override fun resolveBlockEntity(level: net.minecraft.world.level.Level, payload: EndpointPayload) = null
        override fun worldCenter(level: net.minecraft.world.level.Level, payload: EndpointPayload): Vec3? = null
        override fun claims(level: net.minecraft.world.level.Level, worldPos: BlockPos): EndpointPayload? = null
    }

    @BeforeEach fun reset() { EndpointBackends.clearForTests() }

    @Test fun `register and get round-trip`() {
        val a = FakeBackend(ResourceLocation("test", "a"))
        EndpointBackends.register(a)
        assertSame(a, EndpointBackends.get(ResourceLocation("test", "a")))
    }

    @Test fun `unknown id returns null`() {
        assertNull(EndpointBackends.get(ResourceLocation("test", "x")))
    }

    @Test fun `all preserves insertion order`() {
        val a = FakeBackend(ResourceLocation("test", "a"))
        val b = FakeBackend(ResourceLocation("test", "b"))
        EndpointBackends.register(a)
        EndpointBackends.register(b)
        assertEquals(listOf(a, b), EndpointBackends.all().toList())
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointBackendsTest" -i`
Expected: FAIL — `EndpointPayload`/`EndpointBackend`/`EndpointBackends` unresolved.

- [ ] **Step 3: Implement `EndpointPayload`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointPayload.kt
package dev.nitka.nodewire.endpoint

import net.minecraft.core.BlockPos

/**
 * Backend-specific payload identifying one endpoint (a logic block) inside
 * a "block container" — the world itself, a VS ship, a Create contraption.
 *
 * Implementers must expose the Level-routable [blockPos] — i.e. the BlockPos
 * to pass to `Level.getBlockEntity` / `Level.sendBlockUpdated`. For world
 * blocks this is the world coord; for ship blocks it's the ship-local
 * (shipyard) coord, which VS routes correctly through its Level hooks.
 */
interface EndpointPayload {
    val blockPos: BlockPos
}
```

- [ ] **Step 4: Implement `EndpointBackend`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackend.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * A pluggable backend representing one kind of block container
 * (world, VS ship, Create contraption, ...). Add a new container type by
 * implementing this interface and calling [EndpointBackends.register].
 */
interface EndpointBackend {
    val id: ResourceLocation
    val payloadCodec: Codec<out EndpointPayload>

    /** The BE behind this payload, or null if not currently resolvable (unloaded, deleted). */
    fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity?

    /** Current world-space centre of the block this payload points at, for renderers. */
    fun worldCenter(level: Level, payload: EndpointPayload): Vec3?

    /**
     * If [worldPos] (or, for VS, a ship-local pos returned by a ship-aware
     * raycast) belongs to this backend's container, return a payload for it.
     * Otherwise null. The world backend always claims as a fallback.
     */
    fun claims(level: Level, worldPos: BlockPos): EndpointPayload?
}
```

- [ ] **Step 5: Implement `EndpointBackends`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointBackends.kt
package dev.nitka.nodewire.endpoint

import net.minecraft.resources.ResourceLocation

/**
 * Open registry of [EndpointBackend]s. Insertion order matters: [claims]
 * is probed in registration order, and the world backend must be last.
 */
object EndpointBackends {
    private val byId = LinkedHashMap<ResourceLocation, EndpointBackend>()

    fun register(backend: EndpointBackend) { byId[backend.id] = backend }
    fun get(id: ResourceLocation): EndpointBackend? = byId[id]
    fun all(): Collection<EndpointBackend> = byId.values

    /** Test-only: clear between cases so insertion-order assertions are deterministic. */
    internal fun clearForTests() { byId.clear() }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointBackendsTest" -i`
Expected: PASS — all 3 tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/endpoint/ src/test/kotlin/dev/nitka/nodewire/endpoint/
git commit -m "$(cat <<'EOF'
feat(endpoint): registry + interfaces for pluggable block-container backends

Foundation for cross-frame bindings — replaces raw BlockPos endpoints
with a registry-based abstraction so VS ships, Create contraptions, and
future container mods plug in without core edits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `EndpointRef` + tagged-dispatch codec

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointRef.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointRefCodecTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointRefCodecTest.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndpointRefCodecTest {
    data class FakePayload(override val blockPos: BlockPos) : EndpointPayload

    object FakeBackend : EndpointBackend {
        override val id = ResourceLocation("test", "fake")
        override val payloadCodec: Codec<out EndpointPayload> =
            BlockPos.CODEC.xmap(::FakePayload) { it.blockPos }
        override fun resolveBlockEntity(level: Level, payload: EndpointPayload) = null
        override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? = null
        override fun claims(level: Level, worldPos: BlockPos): EndpointPayload? = FakePayload(worldPos)
    }

    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(FakeBackend)
    }

    @Test fun `round-trip preserves backend id and payload`() {
        val ref = EndpointRef(FakeBackend.id, FakePayload(BlockPos(1, 2, 3)))
        val json = EndpointRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).result().orElseThrow()
        val decoded = EndpointRef.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()
        assertEquals(ref, decoded)
    }

    @Test fun `unknown backend id decodes to UnknownPayload`() {
        val raw = """{"backend":"test:missing","payload":[1,2,3]}"""
        val json = com.google.gson.JsonParser.parseString(raw)
        val ref = EndpointRef.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()
        assertEquals(ResourceLocation("test", "missing"), ref.backendId)
        assertTrue(ref.payload is UnknownPayload)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointRefCodecTest" -i`
Expected: FAIL — `EndpointRef`, `UnknownPayload` unresolved.

- [ ] **Step 3: Implement `EndpointRef.kt`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointRef.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * Placeholder payload retained when a binding references a backend that
 * isn't registered in the current session (e.g. mod removed). Preserves
 * the raw NBT so re-adding the mod restores the binding. `resolve()` and
 * `worldCenter()` always return null, so signal flow and rendering skip
 * these silently.
 */
data class UnknownPayload(val raw: CompoundTag) : EndpointPayload {
    override val blockPos: BlockPos = BlockPos.ZERO
}

/**
 * Tagged reference to one endpoint. The pair [backendId] + [payload] is
 * the persisted identity of a binding target.
 */
data class EndpointRef(val backendId: ResourceLocation, val payload: EndpointPayload) {

    fun resolve(level: Level): BlockEntity? =
        EndpointBackends.get(backendId)?.resolveBlockEntity(level, payload)

    fun worldCenter(level: Level): Vec3? =
        EndpointBackends.get(backendId)?.worldCenter(level, payload)

    companion object {
        /**
         * Resolve the right backend for a raycast hit. The world backend
         * (last in registration order) always claims, so this never throws
         * once the world backend is registered.
         */
        fun from(level: Level, hitPos: BlockPos): EndpointRef {
            for (b in EndpointBackends.all()) {
                b.claims(level, hitPos)?.let { return EndpointRef(b.id, it) }
            }
            error("no backend claimed pos $hitPos — was WorldBackend registered?")
        }

        @Suppress("UNCHECKED_CAST")
        val CODEC: Codec<EndpointRef> = RecordCodecBuilder.create { i ->
            i.group(
                ResourceLocation.CODEC.fieldOf("backend").forGetter(EndpointRef::backendId),
                CompoundTag.CODEC.fieldOf("payload").forGetter { ref ->
                    val backend = EndpointBackends.get(ref.backendId)
                    if (backend == null || ref.payload is UnknownPayload) {
                        (ref.payload as? UnknownPayload)?.raw ?: CompoundTag()
                    } else {
                        (backend.payloadCodec as Codec<EndpointPayload>)
                            .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, ref.payload)
                            .result().orElse(CompoundTag()) as CompoundTag
                    }
                },
            ).apply(i) { id, payloadTag ->
                val backend = EndpointBackends.get(id)
                val payload: EndpointPayload = if (backend != null) {
                    (backend.payloadCodec as Codec<EndpointPayload>)
                        .parse(net.minecraft.nbt.NbtOps.INSTANCE, payloadTag)
                        .result().orElse(UnknownPayload(payloadTag))
                } else {
                    UnknownPayload(payloadTag)
                }
                EndpointRef(id, payload)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointRefCodecTest" -i`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointRef.kt src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointRefCodecTest.kt
git commit -m "$(cat <<'EOF'
feat(endpoint): EndpointRef + tagged-dispatch codec with UnknownPayload fallback

Codec encodes {backend: rl, payload: CompoundTag} where payload is
decoded via the registered backend's own codec. Unknown backend id is
preserved as UnknownPayload(raw) so bindings survive mod-removal.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `WorldBackend` + register in `Nodewire.init`

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/endpoint/WorldBackend.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`
- Test: extend `src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt`

- [ ] **Step 1: Write failing test**

Add to `EndpointBackendsTest.kt`:

```kotlin
    @Test fun `WorldBackend claims any position`() {
        EndpointBackends.register(WorldBackend)
        val payload = WorldBackend.claims(
            org.mockito.Mockito.mock(net.minecraft.world.level.Level::class.java),
            BlockPos(7, 8, 9),
        )
        assertNotNull(payload)
        assertEquals(BlockPos(7, 8, 9), payload!!.blockPos)
    }
```

If `org.mockito` is not on the test classpath, replace with `Mockito` alternative or use a simple anonymous Level subclass — check `./gradlew dependencies | grep mockito` first; if absent, swap the test to pass `null as Level` only when WorldBackend doesn't read level (it doesn't):

```kotlin
    @Test fun `WorldBackend claims any position`() {
        EndpointBackends.register(WorldBackend)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val payload = WorldBackend.claims(null as net.minecraft.world.level.Level, BlockPos(7, 8, 9))
        assertNotNull(payload)
        assertEquals(BlockPos(7, 8, 9), payload!!.blockPos)
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointBackendsTest" -i`
Expected: FAIL — `WorldBackend` unresolved.

- [ ] **Step 3: Implement `WorldBackend`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/endpoint/WorldBackend.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

data class WorldPayload(override val blockPos: BlockPos) : EndpointPayload

/**
 * Fallback backend — every block in the regular world belongs here.
 * Registered last so backend-specific claims (VS ship, Create contraption)
 * get first chance.
 */
object WorldBackend : EndpointBackend {
    override val id: ResourceLocation = ResourceLocation("nodewire", "world")
    override val payloadCodec: Codec<out EndpointPayload> =
        BlockPos.CODEC.xmap(::WorldPayload) { it.blockPos }

    override fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity? =
        level.getBlockEntity(payload.blockPos)

    override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? =
        Vec3.atCenterOf(payload.blockPos)

    override fun claims(level: Level, worldPos: BlockPos): EndpointPayload = WorldPayload(worldPos)
}
```

- [ ] **Step 4: Register in `Nodewire.init`**

Edit `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`. Add import:

```kotlin
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.WorldBackend
```

In the `init` block, after `NodewireNetwork.register()`:

```kotlin
        EndpointBackends.register(WorldBackend)
```

VS backend registration is added in Task 12; world stays first for now.

- [ ] **Step 5: Run test**

Run: `./gradlew test --tests "dev.nitka.nodewire.endpoint.EndpointBackendsTest" -i`
Expected: PASS — 4 tests green.

- [ ] **Step 6: Build to confirm no compile breaks**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/endpoint/WorldBackend.kt src/main/kotlin/dev/nitka/nodewire/Nodewire.kt src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt
git commit -m "$(cat <<'EOF'
feat(endpoint): WorldBackend + registration in mod init

Always-on fallback backend. Claims every BlockPos; resolves via
Level.getBlockEntity directly. Registered first; VS ship backend will
be registered ahead of it in a later task so claims order is correct.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Binding data model + codecs (legacy fallback)

### Task 4: `ChannelBinding.targetPos → target: EndpointRef`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt`
- Test: Create `src/test/kotlin/dev/nitka/nodewire/block/ChannelBindingCodecTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/block/ChannelBindingCodecTest.kt
package dev.nitka.nodewire.block

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelBindingCodecTest {
    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(WorldBackend)
    }

    @Test fun `round-trip with EndpointRef target`() {
        val ref = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(4, 5, 6)))
        val cb = ChannelBinding("out", ref, "in")
        val tag = ChannelBinding.CODEC.encodeStart(NbtOps.INSTANCE, cb).result().orElseThrow()
        val decoded = ChannelBinding.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(cb, decoded)
    }

    @Test fun `legacy BlockPos pos field migrates to World EndpointRef`() {
        // Legacy NBT shape: { src: "out", pos: [I; 4,5,6], dst: "in" }
        val legacy = JsonParser.parseString(
            """{"src":"out","pos":[4,5,6],"dst":"in"}"""
        )
        val decoded = ChannelBinding.CODEC.parse(JsonOps.INSTANCE, legacy).result().orElseThrow()
        assertEquals("out", decoded.sourceChannelName)
        assertEquals("in", decoded.targetChannelName)
        assertEquals(WorldBackend.id, decoded.target.backendId)
        assertEquals(BlockPos(4, 5, 6), decoded.target.payload.blockPos)
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.block.ChannelBindingCodecTest" -i`
Expected: FAIL — `target` member missing on `ChannelBinding`.

- [ ] **Step 3: Migrate `ChannelBinding`**

Replace `src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt`:

```kotlin
package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos

/**
 * One cross-block channel link. Target identity is an [EndpointRef], so a
 * binding can point at a logic block in the world, on a VS ship, or in a
 * Create contraption (sub-project #2) without code change.
 *
 * Legacy NBT (pre-2026-05-16) stored `pos: BlockPos`; [LEGACY_CODEC]
 * reads that shape and wraps it as `EndpointRef.World(pos)`.
 */
data class ChannelBinding(
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetChannelName: String,
) {
    companion object {
        private val NEW_CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                EndpointRef.CODEC.fieldOf("target").forGetter(ChannelBinding::target),
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i, ::ChannelBinding)
        }

        private val LEGACY_CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter { it.target.payload.blockPos },
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i) { src, pos, dst ->
                ChannelBinding(src, EndpointRef(WorldBackend.id, WorldPayload(pos)), dst)
            }
        }

        // either(new, legacy): try the new format first; fall back to legacy.
        // Writes always use NEW_CODEC (the first arm).
        val CODEC: Codec<ChannelBinding> = Codec.either(NEW_CODEC, LEGACY_CODEC)
            .xmap(
                { e -> e.map({ it }, { it }) },
                { com.mojang.datafixers.util.Either.left(it) },
            )
    }
}
```

- [ ] **Step 4: Update `LogicBlockEntity` callsites for new `ChannelBinding`**

Edit `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`. Replace ALL references to `binding.targetPos` and `it.targetPos` (within ChannelBinding context) with `binding.target.payload.blockPos`. Specifically:

- `bindings.removeAll { ... it.targetPos == target.blockPos ... }` → `... it.target.payload.blockPos == target.blockPos ...`
- `bindings.add(ChannelBinding(sourceChannelName, target.blockPos, targetChannelName))` → `bindings.add(ChannelBinding(sourceChannelName, dev.nitka.nodewire.endpoint.EndpointRef.from(level!!, target.blockPos), targetChannelName))`
- `removeBinding(... targetPos: BlockPos ...)` signature stays for now (callers still pass world pos); body: `it.target.payload.blockPos == targetPos`
- In `serverTick`: `val target = level.getBlockEntity(binding.targetPos) as? LogicBlockEntity` → `val target = binding.target.resolve(level) as? LogicBlockEntity`
- In `isStale`: `val target = level.getBlockEntity(binding.targetPos) as? LogicBlockEntity` → `val target = binding.target.resolve(level) as? LogicBlockEntity`

Apply the same `it.targetPos` → `it.target.payload.blockPos` substitution everywhere ChannelBinding is used. `SideBinding` is migrated in Task 5; leave its `targetPos` accesses untouched in this task.

- [ ] **Step 5: Run tests + build**

Run: `./gradlew test --tests "dev.nitka.nodewire.block.ChannelBindingCodecTest" -i && ./gradlew build`
Expected: PASS + SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt src/test/kotlin/dev/nitka/nodewire/block/ChannelBindingCodecTest.kt
git commit -m "$(cat <<'EOF'
refactor(binding): ChannelBinding.targetPos → target: EndpointRef

Tagged endpoint identity for cross-frame bindings. Legacy NBT (raw
pos field) migrates to EndpointRef.World on read; writes are always
the new shape.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `SideBinding.targetPos → target: EndpointRef`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt` (side-binding paths)
- Test: extend `src/test/kotlin/dev/nitka/nodewire/block/SideBindingCodecTest.kt`

- [ ] **Step 1: Add failing test cases to `SideBindingCodecTest.kt`**

Read the file first to understand its current shape. Append:

```kotlin
    @org.junit.jupiter.api.BeforeEach fun resetBackends() {
        dev.nitka.nodewire.endpoint.EndpointBackends.clearForTests()
        dev.nitka.nodewire.endpoint.EndpointBackends.register(
            dev.nitka.nodewire.endpoint.WorldBackend
        )
    }

    @org.junit.jupiter.api.Test fun `side-binding round-trip with EndpointRef target`() {
        val ref = dev.nitka.nodewire.endpoint.EndpointRef(
            dev.nitka.nodewire.endpoint.WorldBackend.id,
            dev.nitka.nodewire.endpoint.WorldPayload(net.minecraft.core.BlockPos(7, 8, 9)),
        )
        val sb = SideBinding("ch", ref, net.minecraft.core.Direction.UP, "label")
        val tag = SideBinding.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, sb).result().orElseThrow()
        val decoded = SideBinding.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(sb, decoded)
    }

    @org.junit.jupiter.api.Test fun `legacy side-binding pos field migrates to World ref`() {
        val legacy = com.google.gson.JsonParser.parseString(
            """{"src":"out","pos":[3,4,5],"side":"up","name":"x"}"""
        )
        val decoded = SideBinding.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, legacy)
            .result().orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(
            net.minecraft.core.BlockPos(3, 4, 5), decoded.target.payload.blockPos
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            net.minecraft.core.Direction.UP, decoded.targetSide
        )
        org.junit.jupiter.api.Assertions.assertEquals("x", decoded.name)
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.block.SideBindingCodecTest" -i`
Expected: FAIL — `target` member missing on `SideBinding`.

- [ ] **Step 3: Migrate `SideBinding`**

Replace `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`:

```kotlin
package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Drive-by-wire binding from a named channel on a source LogicBlock to an
 * arbitrary block face. Target identity is an [EndpointRef], so the face
 * can sit on a VS ship or Create contraption block as well as a world
 * block. Signals reach the target via [dev.nitka.nodewire.signal.VirtualSignalMap].
 *
 * Legacy NBT (pre-2026-05-16) stored `pos: BlockPos`; the legacy codec
 * arm wraps it as `EndpointRef.World(pos)`.
 */
data class SideBinding(
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetSide: Direction,
    val name: String = "",
) {
    companion object {
        private val NEW_CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                EndpointRef.CODEC.fieldOf("target").forGetter(SideBinding::target),
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SideBinding::name),
            ).apply(i, ::SideBinding)
        }

        private val LEGACY_CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter { it.target.payload.blockPos },
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SideBinding::name),
            ).apply(i) { src, pos, side, name ->
                SideBinding(src, EndpointRef(WorldBackend.id, WorldPayload(pos)), side, name)
            }
        }

        val CODEC: Codec<SideBinding> = Codec.either(NEW_CODEC, LEGACY_CODEC)
            .xmap(
                { e -> e.map({ it }, { it }) },
                { com.mojang.datafixers.util.Either.left(it) },
            )
    }
}
```

- [ ] **Step 4: Update `LogicBlockEntity` side-binding callsites**

In `LogicBlockEntity.kt` replace ALL `sb.targetPos` and `it.targetPos` (inside SideBinding context) with `sb.target.payload.blockPos` / `it.target.payload.blockPos`. Key spots:

- `addSideBinding(... targetPos: BlockPos, targetSide: Direction)` — signature accepts world pos; body must build `EndpointRef.from(level!!, targetPos)`. Add a guard: if `level == null` return false. Then:
  ```kotlin
  val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level!!, targetPos)
  sideBindings.removeAll {
      it.sourceChannelName == sourceChannelName
          && it.target.payload.blockPos == targetPos
          && it.targetSide == targetSide
  }
  sideBindings.add(SideBinding(sourceChannelName, ref, targetSide))
  ```
- `removeSideBinding(... targetPos: BlockPos, targetSide: Direction)` — match by `it.target.payload.blockPos == targetPos`. The `VirtualSignalMap.put(blockPos, targetPos, ...)` and `level.getBlockState(targetPos)` calls stay (world-pos argument).
- `renameSideBinding(...)` — same `it.target.payload.blockPos == targetPos`.
- `isSideStale(sb, level)` — `level.getBlockState(sb.target.payload.blockPos)`.
- `serverTick`: `sb.targetPos` → `sb.target.payload.blockPos` in 3 places (map.put, level.getBlockState, neighborChanged).
- `setRemoved`: same.

- [ ] **Step 5: Run tests + build**

Run: `./gradlew test --tests "dev.nitka.nodewire.block.SideBindingCodecTest" -i && ./gradlew build`
Expected: PASS + SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt src/test/kotlin/dev/nitka/nodewire/block/SideBindingCodecTest.kt
git commit -m "$(cat <<'EOF'
refactor(binding): SideBinding.targetPos → target: EndpointRef

Mirror of ChannelBinding migration. Legacy NBT auto-migrates on read.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — Packet migrations

### Task 6: `BindChannelPacket` carries `EndpointRef` target

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/BindChannelPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt` (callsite)

- [ ] **Step 1: Edit `BindChannelPacket`**

Replace `targetPos: BlockPos` with `target: EndpointRef`. Update encode/decode codec field from `BlockPos.CODEC.fieldOf("tgt_pos")` to `EndpointRef.CODEC.fieldOf("target")`. Update handle:

```kotlin
class BindChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: dev.nitka.nodewire.endpoint.EndpointRef,
    val targetChannelName: String,
)
```

Inside `handle()`:
- Replace `val tgtBe = level.getBlockEntity(targetPos) as? LogicBlockEntity` with `val tgtBe = target.resolve(level) as? LogicBlockEntity`.
- Error message: `"Target block missing at ${target.payload.blockPos.toShortString()}"`.
- Reach check: `srcRef = EndpointRef.from(level, sourcePos)`, then use `srcRef.worldCenter(level)` for the distance check (falls back to `Vec3.atCenterOf(sourcePos)` if backend missing).

Update CODEC:

```kotlin
val CODEC: Codec<BindChannelPacket> = RecordCodecBuilder.create { i ->
    i.group(
        BlockPos.CODEC.fieldOf("src_pos").forGetter(BindChannelPacket::sourcePos),
        Codec.STRING.fieldOf("src_ch").forGetter(BindChannelPacket::sourceChannelName),
        dev.nitka.nodewire.endpoint.EndpointRef.CODEC.fieldOf("target").forGetter(BindChannelPacket::target),
        Codec.STRING.fieldOf("tgt_ch").forGetter(BindChannelPacket::targetChannelName),
    ).apply(i, ::BindChannelPacket)
}
```

In the handler, when calling `srcBe.tryAddBinding(sourceChannelName, tgtBe, targetChannelName)` — keep as is; the BE method takes the `target: LogicBlockEntity` instance and internally builds the right EndpointRef (Task 4 already adapted `tryAddBinding` to call `EndpointRef.from(level!!, target.blockPos)`).

- [ ] **Step 2: Update `ChannelLinkToolItem.openTargetPicker`**

Change:
```kotlin
NodewireNetwork.CHANNEL.sendToServer(
    BindChannelPacket(sourcePos, sourceName, targetPos, picked),
)
```
to:
```kotlin
val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(mc.level!!, targetPos)
NodewireNetwork.CHANNEL.sendToServer(
    BindChannelPacket(sourcePos, sourceName, targetRef, picked),
)
```

- [ ] **Step 3: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/net/BindChannelPacket.kt src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt
git commit -m "$(cat <<'EOF'
refactor(packet): BindChannelPacket carries EndpointRef target

Server resolves target BE via EndpointRef.resolve; reach check uses
backend.worldCenter so ship-local source positions are compared in
world space.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `BindSideChannelPacket` carries `EndpointRef` target

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/BindSideChannelPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt`

- [ ] **Step 1: Edit `BindSideChannelPacket`**

Same shape as Task 6. Class:

```kotlin
class BindSideChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: dev.nitka.nodewire.endpoint.EndpointRef,
    val targetSide: Direction,
)
```

In `handle()`:
- Reach check via backend.worldCenter as in Task 6.
- `val ok = srcBe.addSideBinding(sourceChannelName, target.payload.blockPos, targetSide)` — `addSideBinding` already takes `BlockPos` and internally calls `EndpointRef.from(level!!, targetPos)` (Task 5). So `target.payload.blockPos` is what we pass. Subtle: when target is on a ship, `payload.blockPos` is the ship-local pos — exactly what `Level.getBlockState`, `neighborChanged`, etc. expect (VS routes by ship-local pos).
- `level.updateNeighborsAt(sourcePos, srcBe.blockState.block)` — keep as is.

Update CODEC:
```kotlin
val CODEC: Codec<BindSideChannelPacket> = RecordCodecBuilder.create { i ->
    i.group(
        BlockPos.CODEC.fieldOf("src_pos").forGetter(BindSideChannelPacket::sourcePos),
        Codec.STRING.fieldOf("src_ch").forGetter(BindSideChannelPacket::sourceChannelName),
        dev.nitka.nodewire.endpoint.EndpointRef.CODEC.fieldOf("target").forGetter(BindSideChannelPacket::target),
        Direction.CODEC.fieldOf("tgt_side").forGetter(BindSideChannelPacket::targetSide),
    ).apply(i, ::BindSideChannelPacket)
}
```

- [ ] **Step 2: Update `ChannelLinkToolItem.handleNonLogicTarget` callsite**

```kotlin
val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(mc.level!!, targetPos)
NodewireNetwork.CHANNEL.sendToServer(
    BindSideChannelPacket(sourcePos, sourceName, targetRef, targetSide),
)
```

Where `mc = Minecraft.getInstance()` already in scope (sibling method already uses it).

- [ ] **Step 3: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/net/BindSideChannelPacket.kt src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt
git commit -m "$(cat <<'EOF'
refactor(packet): BindSideChannelPacket carries EndpointRef target

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `RemoveBindingPacket` carries `EndpointRef` target

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt` (callers)

- [ ] **Step 1: Edit `RemoveBindingPacket`**

Replace `targetPos: BlockPos` with `target: EndpointRef`. In `handle()`:
```kotlin
val targetPos = target.payload.blockPos
when (kind) {
    Kind.CHANNEL -> srcBe.removeBinding(sourceChannelName, targetPos, extra)
    Kind.SIDE -> {
        val side = Direction.byName(extra) ?: return@enqueueWork
        srcBe.removeSideBinding(sourceChannelName, targetPos, side)
    }
}
```

Update CODEC: replace `BlockPos.CODEC.fieldOf("tgt_pos")` with `EndpointRef.CODEC.fieldOf("target")`.

- [ ] **Step 2: Update `BindingsManagerScreen` callsites**

Grep for `RemoveBindingPacket(`. Each call site has a `targetPos: BlockPos` argument; replace with `EndpointRef.from(Minecraft.getInstance().level!!, targetPos)` OR — if the binding object is in scope — pass `binding.target` directly. Prefer the latter:

```kotlin
NodewireNetwork.CHANNEL.sendToServer(
    RemoveBindingPacket(sourcePos, binding.sourceChannelName, binding.target, ...)
)
```

- [ ] **Step 3: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "$(cat <<'EOF'
refactor(packet): RemoveBindingPacket carries EndpointRef target

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `HighlightPacket` carries `EndpointRef`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/HighlightPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/command/HighlightServerCommand.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt` (chat-link writers)

- [ ] **Step 1: Edit `HighlightPacket`**

```kotlin
package dev.nitka.nodewire.net

import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

class HighlightPacket(val endpoint: EndpointRef, val durationMs: Long) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(EndpointRef.CODEC, endpoint)
        buf.writeVarLong(durationMs)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>) {
        val c = ctx.get()
        c.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable {
                    dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
                        .highlight(endpoint, durationMs)
                }
            }
        }
        c.packetHandled = true
    }

    companion object {
        fun decode(buf: FriendlyByteBuf) = HighlightPacket(
            buf.readCodec(EndpointRef.CODEC),
            buf.readVarLong(),
        )
    }
}
```

- [ ] **Step 2: Edit `BlockHighlightRenderer.highlight` signature**

Find current `fun highlight(pos: BlockPos, durationMs: Long)`. Change to `fun highlight(endpoint: EndpointRef, durationMs: Long)`. Internal store keeps `endpoint` instead of `pos`. In the render loop, each entry computes its world centre via `endpoint.worldCenter(level)` per frame (mirrors the wire renderer pattern from Task 11) and skips if null.

Concretely: if the renderer currently has a `data class Entry(val pos: BlockPos, val expiresAt: Long)`, change to `data class Entry(val endpoint: EndpointRef, val expiresAt: Long)`. Inside `drawCubeSolid` (or whatever the current draw method is called), replace `Vec3.atCenterOf(pos)` with `endpoint.worldCenter(level) ?: return@forEach`. Add an overload that accepts `BlockPos` for backward compatibility with any caller still using positions:

```kotlin
fun highlight(pos: BlockPos, durationMs: Long) {
    val level = net.minecraft.client.Minecraft.getInstance().level ?: return
    highlight(EndpointRef.from(level, pos), durationMs)
}
```

- [ ] **Step 3: Update server `HighlightServerCommand`**

Switch the command to take BlockPos as before (existing UX), then convert to EndpointRef before sending. Replace the `HighlightPacket(pos, …)` calls with:

```kotlin
val level = ctx.source.level
val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level, pos)
NodewireNetwork.CHANNEL.send(
    PacketDistributor.PLAYER.with { player },
    HighlightPacket(ref, DEFAULT_DURATION_MS),
)
```

Apply to both the `executes` branch and the `seconds`-argument branch.

- [ ] **Step 4: Update client `HighlightCommand`**

Same conversion: if it currently calls `BlockHighlightRenderer.highlight(pos, …)`, leave it (the BlockPos overload added in Step 2 handles it).

- [ ] **Step 5: Update `BindingsManagerScreen` chat-link writers**

Grep for `/nodewire highlight`. Chat-link click payload uses world-space `BlockPos.toShortString()`-style text — Brigadier `BlockPosArgument` parses world coordinates. For ship blocks, this means the chat link will type a ship-local pos string that, when parsed by Brigadier, refers to a world-space block — wrong for ship targets. Two options:

  - **Simple:** leave chat links unchanged; they continue to highlight world blocks correctly. Ship blocks won't have a usable chat-link, but the Bindings Manager UI button (which directly calls `BlockHighlightRenderer.highlight(endpoint, …)`) still works for ship targets.
  - **Full fix:** introduce a stable backend-id-aware chat token. Out of scope for this plan.

Go with **Simple**: change any direct call from `BlockHighlightRenderer.highlight(binding.targetPos, …)` to `BlockHighlightRenderer.highlight(binding.target, …)`. The chat-link generator that builds `"/nodewire highlight x y z"` stays world-pos-only; it's OK because ship-block bindings are uncommon to reference via chat.

- [ ] **Step 6: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/net/HighlightPacket.kt src/main/kotlin/dev/nitka/nodewire/command/HighlightServerCommand.kt src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "$(cat <<'EOF'
refactor(highlight): HighlightPacket + renderer accept EndpointRef

Ship-block highlights now follow the ship visually. Chat-link command
keeps world-pos UX (legacy); UI buttons call into the renderer with
the binding's EndpointRef directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — Wire renderer transform

### Task 10: `WireWorldRenderer` per-endpoint world transform

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt`

- [ ] **Step 1: Switch `RenderBinding`/`RenderSideBinding` to carry world centres up front**

Reading the current renderer (lines 79–106 of `WireWorldRenderer.kt`): it builds `bindList` and `sideList` by iterating `source.bindingsSnapshot()` and `source.sideBindingsSnapshot()`. Replace the `b.targetPos.asLong()` keying with `b.target.payload.blockPos.asLong()` so the fan-out keying still works (uniquely keys per *target block*, which is what we want).

For the actual render, replace direct uses of `b.targetPos.center` and `sb.binding.targetPos.center` with backend-resolved world centres:

```kotlin
for (rb in bindList) {
    val srcKey = rb.source.blockPos.asLong()
    val dstKey = rb.binding.target.payload.blockPos.asLong()
    val srcCenter = sourceWorldCenter(rb.source, level) ?: continue
    val dstCenter = rb.binding.target.worldCenter(level) ?: continue
    val src = fanOffset(srcCenter, rb.srcIdx, outTotal[srcKey]!!)
    val dst = fanOffset(dstCenter, rb.dstIdx, inTotal[dstKey]!!)
    val color = colorForBinding(rb.source, rb.binding.sourceChannelName)
    drawStraightWire(builder, matrix, src, dst, cameraPos, color)
}
for (sb in sideList) {
    val srcKey = sb.source.blockPos.asLong()
    val srcCenter = sourceWorldCenter(sb.source, level) ?: continue
    val tCenter = sb.binding.target.worldCenter(level) ?: continue
    val src = fanOffset(srcCenter, sb.srcIdx, outTotal[srcKey]!!)
    val n = sb.binding.targetSide.normal
    val dst = net.minecraft.world.phys.Vec3(
        tCenter.x + n.x * 0.5,
        tCenter.y + n.y * 0.5,
        tCenter.z + n.z * 0.5,
    )
    val color = colorForBinding(sb.source, sb.binding.sourceChannelName)
    drawStraightWire(builder, matrix, src, dst, cameraPos, color)
    drawFaceFrame(builder, matrix, sb.binding.target.payload.blockPos, sb.binding.targetSide, color)
}
```

Add a helper to map a *source* LogicBlockEntity to a world centre — the source's own ship membership has to be re-derived each frame because the BE doesn't store its `EndpointRef`:

```kotlin
private fun sourceWorldCenter(be: dev.nitka.nodewire.block.LogicBlockEntity, level: net.minecraft.world.level.Level): net.minecraft.world.phys.Vec3? {
    val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level, be.blockPos)
    return ref.worldCenter(level)
}
```

`level` is `mc.level!!` already loaded at the top of `render()`. Pass it in (small refactor: hoist `val level = mc.level ?: return` up before constructing the lists, which it already is at line 65).

`drawFaceFrame` is called with a `BlockPos` argument that it treats as `x/y/z` for the face plane (lines 174–181). For ship targets that's a ship-local pos — we need to project it through ship transform too. Simpler: compute the face plane centre from `target.worldCenter(level) + n*(0.5 + outset)` and refactor `drawFaceFrame` to accept a `Vec3` centre + face direction. Replace the call:

```kotlin
drawFaceFrame(builder, matrix, dst, sb.binding.targetSide, color)
```

And change `drawFaceFrame` signature:

```kotlin
private fun drawFaceFrame(
    builder: VertexConsumer,
    matrix: Matrix4f,
    faceCenter: Vec3,
    face: Direction,
    color: Int,
) {
    val outset = 0.005
    val n = face.normal
    val fx = faceCenter.x + n.x * outset
    val fy = faceCenter.y + n.y * outset
    val fz = faceCenter.z + n.z * outset
    // ... rest unchanged: basis, edges, etc., but cx/cy/cz no longer
    // needed because faceCenter is already at face-plane centre.
}
```

Where `dst` (recomputed already at face-plane centre via `tCenter + n*0.5`) is the right input. Adjust the outset to be ADDITIONAL beyond the 0.5 already in `dst`.

(Note: this changes face-frame visual offset by removing one redundant 0.5; the current code added 0.5 inside drawFaceFrame after the caller passed `pos` — verify against current behaviour during in-game test step.)

- [ ] **Step 2: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt
git commit -m "$(cat <<'EOF'
refactor(wire): renderer resolves endpoint world centre per frame

Wires now follow VS ships: source + target endpoints are looked up via
EndpointBackends each frame, returning interpolated ship transforms
where applicable. Wires for endpoints whose ship is unloaded skip
silently and reappear when the ship re-enters render distance.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — VS backend + Channel Link Tool

### Task 11: `VsShipBackend` implementation + register

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/vs/VsShipBackend.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`

- [ ] **Step 1: Implement `VsShipBackend`**

VS 2.x for 1.20.1 publishes the kotlin extension `VSGameUtilsKt`:
- `VSGameUtilsKt.getShipObjectManagingPos(level: Level, pos: BlockPos): LoadedShip?`
- `VSGameUtilsKt.getShipObjectManagingPos(level: Level, x: Int, y: Int, z: Int): LoadedShip?`
- On the client: `getShipObjectManagingPos` likewise; ship's `renderTransform.shipToWorld` is a `Matrix4dc`.

Type names: ship's `id` is `Long`. World→ship lookup: `level.shipObjectWorld.allShips[shipId]` exists but the public Kotlin API surface is `VSGameUtilsKt.getShipObjectWorld(level)` + `getAllShips()` etc. We use `getShipObjectManagingPos` for lookups by pos and `level.shipObjectWorld.loadedShips[shipId]` (cast as needed) for lookup by id.

```kotlin
// src/main/kotlin/dev/nitka/nodewire/integration/vs/VsShipBackend.kt
package dev.nitka.nodewire.integration.vs

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointBackend
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.common.VSGameUtilsKt

/**
 * VS-ship backend. Active only when valkyrienskies is loaded; gated by
 * [register] which is called from [dev.nitka.nodewire.Nodewire.init].
 *
 * Payload is (shipId, ship-local BlockPos). `claims` consults VS to find
 * the ship managing a given block position — for both ship-local positions
 * (the input from VS-aware raycasts) and world positions (returns null,
 * letting WorldBackend claim downstream).
 */
data class VsShipPayload(val shipId: Long, override val blockPos: BlockPos) : EndpointPayload

object VsShipBackend : EndpointBackend {
    override val id: ResourceLocation = ResourceLocation("nodewire", "vs_ship")

    override val payloadCodec: Codec<out EndpointPayload> = RecordCodecBuilder.create { i ->
        i.group(
            Codec.LONG.fieldOf("ship").forGetter(VsShipPayload::shipId),
            BlockPos.CODEC.fieldOf("pos").forGetter(VsShipPayload::blockPos),
        ).apply(i, ::VsShipPayload)
    }

    override fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity? {
        val p = payload as? VsShipPayload ?: return null
        return level.getBlockEntity(p.blockPos)
    }

    override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? {
        val p = payload as? VsShipPayload ?: return null
        val ship = VSGameUtilsKt.getShipObjectWorld(level).loadedShips.getById(p.shipId) ?: return null
        val center = Vector3d(p.blockPos.x + 0.5, p.blockPos.y + 0.5, p.blockPos.z + 0.5)
        // shipToWorld is a Matrix4dc; transform the local centre into world space.
        ship.renderTransform.shipToWorld.transformPosition(center)
        return Vec3(center.x, center.y, center.z)
    }

    override fun claims(level: Level, worldPos: BlockPos): EndpointPayload? {
        val ship = VSGameUtilsKt.getShipObjectManagingPos(level, worldPos) ?: return null
        return VsShipPayload(ship.id, worldPos)
    }

    /** Idempotent — call from `Nodewire.init` behind `ModList.isLoaded("valkyrienskies")`. */
    fun register() {
        EndpointBackends.register(this)
    }
}
```

NOTE: VS API method names — verify against the current `org.valkyrienskies:valkyrienskies-120-forge:2.4.10` jar:
- `VSGameUtilsKt.getShipObjectManagingPos(Level, BlockPos)` returns `LoadedShip?` (Server side) or `ShipObjectClient?` (client) — the public superinterface is `Ship` which exposes `id: Long`.
- `VSGameUtilsKt.getShipObjectWorld(Level)` returns a `ShipObjectWorld<*>` which has `.loadedShips` (a query-able registry).
- `Ship.renderTransform.shipToWorld` exists on the client; on the server only `transform.shipToWorld` exists. For renderer use we're already client-side. For `worldCenter` on the server (used by `BindChannelPacket` reach check) we need to handle both cases. Safe approach: try `renderTransform` first, fall back to `transform`:

  ```kotlin
  val matrix = try { ship.javaClass.getMethod("getRenderTransform").invoke(ship) }
      catch (_: NoSuchMethodException) { null }
  ```

  but this is reflection, which the user vetoed. Instead, branch on `level.isClientSide`:

  ```kotlin
  val matrix = if (level.isClientSide) {
      (ship as org.valkyrienskies.core.api.ships.ClientShip).renderTransform.shipToWorld
  } else {
      ship.transform.shipToWorld
  }
  ```

  Verify exact type names before commit by reading `./gradlew dependencies --configuration runtimeClasspath | grep valkyrienskies` and inspecting the jar. If type names differ, adjust accordingly. The structure (client has interpolated transform; server has authoritative transform) is stable across VS 2.x.

- [ ] **Step 2: Register VS backend in `Nodewire.init`**

Edit `src/main/kotlin/dev/nitka/nodewire/Nodewire.kt`. Add:

```kotlin
import net.minecraftforge.fml.ModList
```

In `init` block, **before** `EndpointBackends.register(WorldBackend)` so VS gets first claim:

```kotlin
        if (ModList.get().isLoaded("valkyrienskies")) {
            dev.nitka.nodewire.integration.vs.VsShipBackend.register()
        }
        EndpointBackends.register(WorldBackend)
```

`ModList` at `Nodewire.init` time: the mod-loading event has fired by the time the `@Mod` object's `init` runs, so `ModList.get()` is valid. (Confirmed by existing usage in `BindSideChannelPacket` etc.)

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS — VS imports compile because `valkyrienskies-120-forge` is `modImplementation` in `build.gradle.kts`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/vs/VsShipBackend.kt src/main/kotlin/dev/nitka/nodewire/Nodewire.kt
git commit -m "$(cat <<'EOF'
feat(vs): VsShipBackend + conditional registration

Direct VS API imports (no reflection). Backend registered ahead of
WorldBackend so ship blocks get first claim. Server reach-check uses
ship.transform.shipToWorld; renderer uses ship.renderTransform.shipToWorld
for jitter-free interpolation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: VS-aware raycast in `ChannelLinkToolItem`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt`

The current tool uses `UseOnContext` whose `clickedPos` comes from Forge's interaction pipeline. Forge's `RightClickBlock` event for VS ships is already routed by VS through their `Level` raycast hooks — `ctx.clickedPos` is already ship-local for ship hits. **Verification step in-game required.** If it isn't, we need to swap in an explicit `RaycastUtilsKt.clipIncludeShips` at the call point.

- [ ] **Step 1: Verify behaviour in-game (manual)**

The implementer launches client, places a logic block on a VS ship, right-clicks it with Channel Link Tool, and observes:
- If the BE is found by `level.getBlockEntity(pos)` in `handle()` → no further change needed.
- If `be` is null → VS is not routing `UseOnContext.clickedPos`; we need to use ship-aware raycast.

If the verification reveals null:

- [ ] **Step 2 (conditional): Add explicit VS raycast helper**

Edit `ChannelLinkToolItem.handle`:

```kotlin
private fun resolveShipAwarePos(player: net.minecraft.world.entity.player.Player, ctx: UseOnContext): BlockPos {
    if (!ModList.get().isLoaded("valkyrienskies")) return ctx.clickedPos
    val eyes = player.getEyePosition(1f)
    val end = eyes.add(player.getViewVector(1f).scale(8.0))
    val hit = org.valkyrienskies.mod.common.util.RaycastUtilsKt.clipIncludeShips(
        ctx.level,
        net.minecraft.world.level.ClipContext(
            eyes, end,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player,
        ),
    )
    return hit.blockPos
}
```

And use it: `val pos = resolveShipAwarePos(player, ctx)` instead of `val pos = ctx.clickedPos`.

If verification passes (Forge already routes correctly via VS), skip Step 2 entirely.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit (only if Step 2 was needed)**

```bash
git add src/main/kotlin/dev/nitka/nodewire/item/ChannelLinkToolItem.kt
git commit -m "$(cat <<'EOF'
feat(linktool): VS-aware raycast for ship-block targeting

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6 — Final validation

### Task 13: Full test suite + manual integration

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS.

- [ ] **Step 2: Run build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Manual in-game test plan (executed by USER, not the agent — agent reports tasks to user for runClient steps)**

The agent must NOT launch `./gradlew runClient`. List the manual checks for the user to run:

1. **World↔world regression:** place two LogicBlocks on the ground; bind output→input via Channel Link Tool. Verify wire renders + signal propagates. Verify legacy world (saved before this branch) loads without errors and existing bindings still work.
2. **World↔ship:** spawn a small VS ship via `/vs create`; place a LogicBlock on the ship deck; bind from a world LogicBlock → ship LogicBlock. Verify wire renders, follows ship as it moves, signal propagates.
3. **Ship↔ship:** two ships, one LogicBlock on each; bind across. Verify wire follows both ships.
4. **Ship unload/reload:** fly so one ship leaves render distance; wire disappears. Fly back; wire reappears, signal resumes.
5. **Highlight from Bindings Manager:** open Bindings Manager on a ship-block source; click the highlight button for a ship-target binding. Verify the lit-face overlay appears on the ship block.
6. **Drive-by-wire side bindings:** bind a ship-block's BOOL output to a redstone lamp on a world block. Verify the lamp turns on. Reverse direction: bind a world LogicBlock's output to a face of a ship block (e.g. a piston on the ship). Verify the piston extends.

- [ ] **Step 4: Final commit if any cleanup was needed (otherwise skip)**

If any small fixes emerged from Steps 1–3 above, commit them as `fix(vs-ships): ...`.

---

## Out of scope (separate sub-projects)

- `nodewire:create_contraption` backend (sub-project #2 — Create + Create: Interactive).
- "Cleanup dangling bindings" UI in Bindings Manager.
- Backend-aware chat-link `/nodewire highlight` so ship-block highlights work from Bindings Manager chat output.

## Reference

- VS 2.x API for 1.20.1: `org.valkyrienskies.mod.common.VSGameUtilsKt`, `org.valkyrienskies.core.api.ships.Ship`. Verify exact symbol names per current `valkyrienskies-120-forge:2.4.10` jar before committing Task 11. (See `./gradlew dependencies --configuration runtimeClasspath`.)
- Forge `modImplementation` already declares VS at `build.gradle.kts:134` — direct imports compile and run; `ModList.isLoaded` is the only runtime gate.
