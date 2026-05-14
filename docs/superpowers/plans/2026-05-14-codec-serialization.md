# Codec-based serialization — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hand-written `toNbt() / fromNbt()` across the graph model, bindings, and four network packets with Mojang `Codec<T>` instances. One serialization layer for save and wire, free SNBT round-trip via `NbtUtils.structureToSnbt` / `TagParser`.

**Architecture:** Each serializable type gets a `val CODEC: Codec<T>` in its companion. Records use `RecordCodecBuilder.create { ... }`; sealed `PinValue` uses `Codec.STRING.dispatch`. `LogicBlockEntity` reads/writes via `codec.encodeStart(NbtOps.INSTANCE, value)` and `codec.parse(NbtOps.INSTANCE, tag)`. Packets carry the value type directly (not a `CompoundTag` shell) and encode via two small `FriendlyByteBuf` extension functions that wrap codec → tag → buf.

**Tech Stack:** Kotlin 2.0.20, Minecraft 1.20.1 Forge, DataFixerUpper (Mojang Codec / `RecordCodecBuilder` / `Codec.STRING.dispatch`), `NbtOps`, `NbtUtils.structureToSnbt`, `TagParser`.

---

## File Structure

**Create:**
- `src/main/kotlin/dev/nitka/nodewire/net/CodecBufExtensions.kt` — `FriendlyByteBuf.writeCodec` / `readCodec` extension functions.
- `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt` — NBT + SNBT round-trip per codec, populated incrementally as codecs are added.

**Modify (add `val CODEC` in companion, drop legacy `toNbt`/`fromNbt`):**
- `src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/PinRef.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/Edge.kt`
- `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` (also includes `CanvasPos.CODEC` in same file)
- `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt`
- `src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt`
- `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`

**Modify (use codecs at call sites):**
- `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`
- `src/main/kotlin/dev/nitka/nodewire/net/SaveGraphPacket.kt`
- `src/main/kotlin/dev/nitka/nodewire/net/BindChannelPacket.kt`
- `src/main/kotlin/dev/nitka/nodewire/net/BindSideChannelPacket.kt`
- `src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt`

**Modify (existing tests subsumed by codec tests — rewrite or delete):**
- `src/test/kotlin/dev/nitka/nodewire/graph/NodeGraphNbtTest.kt` — rewrite to call codec.
- `src/test/kotlin/dev/nitka/nodewire/net/SaveGraphPacketTest.kt` — rewrite to call codec.

---

### Task 1: Codec ↔ FriendlyByteBuf bridge

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/net/CodecBufExtensions.kt`
- Test: in `CodecRoundTripTest.kt` (created in Task 2 first; this task's tests live in `BufCodecTest.kt`)
- Create: `src/test/kotlin/dev/nitka/nodewire/net/BufCodecTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/net/BufCodecTest.kt
package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private data class TwoInts(val a: Int, val b: Int) {
    companion object {
        val CODEC: Codec<TwoInts> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("a").forGetter(TwoInts::a),
                Codec.INT.fieldOf("b").forGetter(TwoInts::b),
            ).apply(i, ::TwoInts)
        }
    }
}

class BufCodecTest {
    @Test
    fun roundTripThroughBuf() {
        val original = TwoInts(7, -3)
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeCodec(TwoInts.CODEC, original)
        val decoded = buf.readCodec(TwoInts.CODEC)
        assertEquals(original, decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.net.BufCodecTest"`
Expected: compilation error — `writeCodec` / `readCodec` unresolved.

- [ ] **Step 3: Implement the extensions**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/net/CodecBufExtensions.kt
package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.FriendlyByteBuf

/**
 * Bridge between Mojang Codec and the network buffer. Codecs encode to a
 * [net.minecraft.nbt.Tag], so we wrap the result as a [CompoundTag] (every
 * record-codec produces a CompoundTag) and ship it via the buffer's
 * built-in NBT writer.
 *
 * One serialization layer: the same codec drives both BlockEntity NBT and
 * SimpleChannel packets.
 */
fun <T> FriendlyByteBuf.writeCodec(codec: Codec<T>, value: T) {
    val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result()
        .orElseThrow { IllegalStateException("Codec encode failed for $value") }
    val tag = encoded as? CompoundTag
        ?: error("Top-level codec must produce a CompoundTag, got ${encoded::class.simpleName}")
    writeNbt(tag)
}

fun <T> FriendlyByteBuf.readCodec(codec: Codec<T>): T {
    val tag = readNbt() ?: error("readNbt() returned null — buffer empty?")
    return codec.parse(NbtOps.INSTANCE, tag).result()
        .orElseThrow { IllegalStateException("Codec decode failed for tag $tag") }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.net.BufCodecTest"`
Expected: 1 PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/net/CodecBufExtensions.kt \
        src/test/kotlin/dev/nitka/nodewire/net/BufCodecTest.kt
git commit -m "feat(net): writeCodec/readCodec extensions on FriendlyByteBuf"
```

---

### Task 2: PinType + Pin codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

This task creates the round-trip test harness used by every subsequent codec task.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`:

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodecRoundTripTest {

    /** Encode → decode through NbtOps. Asserts the result equals the input. */
    private fun <T> roundTripNbt(codec: Codec<T>, value: T) {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result().orElseThrow()
        val decoded = codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow()
        assertEquals(value, decoded)
    }

    /** Encode → SNBT → parse → decode. Asserts the result equals the input. */
    private fun <T> roundTripSnbt(codec: Codec<T>, value: T) {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result().orElseThrow()
        val snbt = NbtUtils.structureToSnbt(encoded as net.minecraft.nbt.CompoundTag)
        val parsed = TagParser.parseTag(snbt)
        val decoded = codec.parse(NbtOps.INSTANCE, parsed).result().orElseThrow()
        assertEquals(value, decoded)
    }

    @Test fun pinTypeBoolNbt()  = roundTripNbt(PinType.CODEC.wrappedInRecord(), PinType.BOOL)
    @Test fun pinTypeIntNbt()   = roundTripNbt(PinType.CODEC.wrappedInRecord(), PinType.INT)
    @Test fun pinTypeQuatSnbt() = roundTripSnbt(PinType.CODEC.wrappedInRecord(), PinType.QUAT)

    @Test fun pinNbt() = roundTripNbt(Pin.CODEC, Pin("out", "Output", PinType.FLOAT))
    @Test fun pinSnbt() = roundTripSnbt(Pin.CODEC, Pin("a", "A", PinType.VEC3))

    /**
     * PinType.CODEC is a primitive-string codec, which encodes to a
     * StringTag — but our roundTrip helpers and SNBT path require a
     * CompoundTag. Wrap any primitive codec in a 1-field record for the
     * test.
     */
    private fun Codec<PinType>.wrappedInRecord(): Codec<PinType> =
        com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
            i.group(this.fieldOf("v").forGetter { it }).apply(i) { it }
        }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: compilation error — `PinType.CODEC` and `Pin.CODEC` unresolved.

- [ ] **Step 3: Add `PinType.CODEC`**

Append to `src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt` inside the existing `companion object`:

```kotlin
    /**
     * String codec — encodes as the enum's [name]; decode defends with
     * [fromName] which falls back to BOOL on unknown values, preserving
     * the project's forward-compat-load rule.
     */
    val CODEC: com.mojang.serialization.Codec<PinType> =
        com.mojang.serialization.Codec.STRING.xmap(::fromName, PinType::name)
```

- [ ] **Step 4: Add `Pin.CODEC`**

Replace `src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt` body to add a companion:

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A single typed slot on a node. [id] is unique within its parent node's
 * input list (or output list) and is what [PinRef] uses to address the
 * pin — display [name] is for the UI and can be changed without breaking
 * saved graphs.
 *
 * Input vs output is determined by which list of [Node] the pin lives in,
 * not by a field here.
 */
data class Pin(
    val id: String,
    val name: String,
    val type: PinType,
) {
    companion object {
        val CODEC: Codec<Pin> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("id").forGetter(Pin::id),
                Codec.STRING.fieldOf("name").forGetter(Pin::name),
                PinType.CODEC.fieldOf("type").forGetter(Pin::type),
            ).apply(i, ::Pin)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: 5 PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(graph): PinType + Pin codecs"
```

---

### Task 3: PinValue codec (sealed dispatch)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CodecRoundTripTest.kt`:

```kotlin
    @Test fun pinValueBoolNbt()      = roundTripNbt(PinValue.CODEC, PinValue.Bool(true))
    @Test fun pinValueBoolFalseNbt() = roundTripNbt(PinValue.CODEC, PinValue.Bool(false))
    @Test fun pinValueIntNbt()       = roundTripNbt(PinValue.CODEC, PinValue.Int(-42))
    @Test fun pinValueRedstoneNbt()  = roundTripNbt(PinValue.CODEC, PinValue.Redstone(11))
    @Test fun pinValueFloatNbt()     = roundTripNbt(PinValue.CODEC, PinValue.Float(3.14f))
    @Test fun pinValueStrNbt()       = roundTripNbt(PinValue.CODEC, PinValue.Str("hello"))
    @Test fun pinValueVec2Nbt()      = roundTripNbt(PinValue.CODEC, PinValue.Vec2(1f, 2f))
    @Test fun pinValueVec3Nbt()      = roundTripNbt(PinValue.CODEC, PinValue.Vec3(1f, 2f, 3f))
    @Test fun pinValueQuatNbt()      = roundTripNbt(PinValue.CODEC, PinValue.Quat(0f, 0f, 0f, 1f))

    @Test fun pinValueBoolSnbt()  = roundTripSnbt(PinValue.CODEC, PinValue.Bool(true))
    @Test fun pinValueVec3Snbt()  = roundTripSnbt(PinValue.CODEC, PinValue.Vec3(1f, 2f, 3f))
    @Test fun pinValueStrSnbt()   = roundTripSnbt(PinValue.CODEC, PinValue.Str("with spaces"))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: 12 new tests fail (unresolved `PinValue.CODEC`).

- [ ] **Step 3: Add `PinValue.CODEC`**

Append to `PinValue.kt`'s `companion object`:

```kotlin
        // ── Per-variant codecs ────────────────────────────────────────
        private val BoolCodec: com.mojang.serialization.Codec<Bool> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.BOOL.fieldOf("v").forGetter(Bool::value))
                    .apply(i, ::Bool)
            }
        private val IntCodec: com.mojang.serialization.Codec<Int> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Int::value))
                    .apply(i, ::Int)
            }
        private val RedstoneCodec: com.mojang.serialization.Codec<Redstone> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Redstone::value))
                    .apply(i, ::Redstone)
            }
        private val FloatCodec: com.mojang.serialization.Codec<Float> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.FLOAT.fieldOf("v").forGetter(Float::value))
                    .apply(i, ::Float)
            }
        private val StrCodec: com.mojang.serialization.Codec<Str> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.STRING.fieldOf("v").forGetter(Str::value))
                    .apply(i, ::Str)
            }
        private val Vec2Codec: com.mojang.serialization.Codec<Vec2> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec2::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec2::y),
                ).apply(i, ::Vec2)
            }
        private val Vec3Codec: com.mojang.serialization.Codec<Vec3> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec3::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec3::y),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("z").forGetter(Vec3::z),
                ).apply(i, ::Vec3)
            }
        private val QuatCodec: com.mojang.serialization.Codec<Quat> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Quat::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Quat::y),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("z").forGetter(Quat::z),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("w").forGetter(Quat::w),
                ).apply(i, ::Quat)
            }

        /**
         * Sealed dispatch: emits a `type` field plus the variant's fields
         * inline. Decode looks at `type` and routes to the per-variant
         * codec. Adding a new variant means: new data class, new per-
         * variant codec, two more entries here.
         */
        val CODEC: com.mojang.serialization.Codec<PinValue> = com.mojang.serialization.Codec.STRING.dispatch(
            "type",
            { pv -> typeKey(pv) },
            { key -> codecFor(key) },
        )

        private fun typeKey(pv: PinValue): String = when (pv) {
            is Bool     -> "bool"
            is Int      -> "int"
            is Redstone -> "redstone"
            is Float    -> "float"
            is Str      -> "str"
            is Vec2     -> "vec2"
            is Vec3     -> "vec3"
            is Quat     -> "quat"
        }

        private fun codecFor(key: String): com.mojang.serialization.Codec<out PinValue> = when (key) {
            "bool"     -> BoolCodec
            "int"      -> IntCodec
            "redstone" -> RedstoneCodec
            "float"    -> FloatCodec
            "str"      -> StrCodec
            "vec2"     -> Vec2Codec
            "vec3"     -> Vec3Codec
            "quat"     -> QuatCodec
            else       -> error("Unknown PinValue type key: $key")
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: all 17 PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(graph): PinValue codec via sealed dispatch"
```

---

### Task 4: PinRef + Edge codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/PinRef.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Edge.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CodecRoundTripTest.kt`:

```kotlin
    private val nodeA: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val nodeB: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test fun pinRefNbt()   = roundTripNbt(PinRef.CODEC, PinRef(nodeA, "out"))
    @Test fun pinRefSnbt()  = roundTripSnbt(PinRef.CODEC, PinRef(nodeA, "out"))
    @Test fun edgeNbt()     = roundTripNbt(Edge.CODEC, Edge(PinRef(nodeA, "out"), PinRef(nodeB, "in")))
    @Test fun edgeSnbt()    = roundTripSnbt(Edge.CODEC, Edge(PinRef(nodeA, "out"), PinRef(nodeB, "in")))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: 4 new tests fail (unresolved).

- [ ] **Step 3: Add `PinRef.CODEC`**

Replace `PinRef.kt`:

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias NodeId = UUID

/**
 * Stable reference to a specific pin: which node owns it (by [NodeId]) and
 * which pin on that node (by [Pin.id]). Used as the endpoints of an [Edge].
 */
data class PinRef(val node: NodeId, val pin: String) {
    companion object {
        /** UUID round-trips as its canonical string form. */
        private val UUID_CODEC: Codec<UUID> =
            Codec.STRING.xmap(UUID::fromString, UUID::toString)

        val CODEC: Codec<PinRef> = RecordCodecBuilder.create { i ->
            i.group(
                UUID_CODEC.fieldOf("node").forGetter(PinRef::node),
                Codec.STRING.fieldOf("pin").forGetter(PinRef::pin),
            ).apply(i, ::PinRef)
        }
    }
}
```

- [ ] **Step 4: Add `Edge.CODEC`**

Replace `Edge.kt`:

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * Directed connection from an output pin ([from]) to an input pin ([to]).
 * The graph is a DAG — server-side validation in `SaveGraphPacket` rejects
 * cycles.
 *
 * Type compatibility (`from.type == to.type`) is enforced at edit time
 * (UI shows red drag-preview on mismatch) and re-checked on save.
 */
data class Edge(val from: PinRef, val to: PinRef) {
    companion object {
        val CODEC: Codec<Edge> = RecordCodecBuilder.create { i ->
            i.group(
                PinRef.CODEC.fieldOf("from").forGetter(Edge::from),
                PinRef.CODEC.fieldOf("to").forGetter(Edge::to),
            ).apply(i, ::Edge)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: all 21 PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/graph/PinRef.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/Edge.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(graph): PinRef + Edge codecs"
```

---

### Task 5: CanvasPos + Node codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CodecRoundTripTest.kt`:

```kotlin
    @Test fun canvasPosNbt() = roundTripNbt(CanvasPos.CODEC, CanvasPos(1.5f, -2.5f))
    @Test fun canvasPosSnbt() = roundTripSnbt(CanvasPos.CODEC, CanvasPos(0f, 0f))

    @Test fun nodeNbt() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("period", 20) }
        val n = Node(
            id = nodeA,
            typeKey = net.minecraft.resources.ResourceLocation("nodewire", "timer"),
            pos = CanvasPos(10f, 20f),
            inputs = emptyList(),
            outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
            config = cfg,
        )
        roundTripNbt(Node.CODEC, n)
    }

    @Test fun nodeSnbt() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putString("name", "speed"); putString("type", "INT") }
        val n = Node(
            id = nodeB,
            typeKey = net.minecraft.resources.ResourceLocation("nodewire", "channel_output"),
            pos = CanvasPos(-50f, 5f),
            inputs = listOf(Pin("in", "Value", PinType.INT)),
            outputs = emptyList(),
            config = cfg,
        )
        roundTripSnbt(Node.CODEC, n)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: 4 new tests fail (unresolved `CanvasPos.CODEC`, `Node.CODEC`).

- [ ] **Step 3: Replace `Node.kt` with codec-based version**

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * Canvas-space position of a [Node]. Unbounded floats; the editor pans /
 * zooms over this coordinate space freely. Z-order is purely insertion-
 * based — there's no explicit layer field.
 */
data class CanvasPos(val x: Float, val y: Float) {
    companion object {
        val Zero = CanvasPos(0f, 0f)
        val CODEC: Codec<CanvasPos> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.FLOAT.fieldOf("x").forGetter(CanvasPos::x),
                Codec.FLOAT.fieldOf("y").forGetter(CanvasPos::y),
            ).apply(i, ::CanvasPos)
        }
    }
}

/**
 * One vertex in the graph. [typeKey] points to a `NodeType` registry entry
 * which provides the display name and pin layout factory; the layout is
 * also serialized per-instance so a graph loaded with an unregistered type
 * still round-trips (we can't render or evaluate it, but we don't lose it).
 *
 * [config] holds type-specific settings — a constant node stores its value
 * here; a timer stores its period.
 */
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    var pos: CanvasPos,
    var inputs: List<Pin>,
    var outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
) {
    companion object {
        private val UUID_CODEC: Codec<UUID> =
            Codec.STRING.xmap(UUID::fromString, UUID::toString)

        val CODEC: Codec<Node> = RecordCodecBuilder.create { i ->
            i.group(
                UUID_CODEC.fieldOf("id").forGetter(Node::id),
                ResourceLocation.CODEC.fieldOf("type").forGetter(Node::typeKey),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Node::pos),
                Pin.CODEC.listOf().fieldOf("inputs").forGetter(Node::inputs),
                Pin.CODEC.listOf().fieldOf("outputs").forGetter(Node::outputs),
                CompoundTag.CODEC.fieldOf("config").forGetter(Node::config),
            ).apply(i, ::Node)
        }

        fun newId(): NodeId = UUID.randomUUID()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: all 25 PASS.

Note: `Node.kt` previously contained `toNbt` / `fromNbt` and helper `writePinList` / `readPinList`. Those are gone now. Any caller still using them will break compilation in later tasks; that's intentional. Run `./gradlew compileKotlin` separately if you want to see where it breaks now:

```bash
cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin 2>&1 | grep -E "Node\.(toNbt|fromNbt)|writePinList|readPinList" | head -10
```

Expect callers in `NodeGraph.kt` (still using `Node.toNbt`/`fromNbt`). Those compile errors are fixed by Task 6.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/graph/Node.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(graph): CanvasPos + Node codecs (drops Node.toNbt/fromNbt)"
```

If `git commit` fails because compileKotlin is wired into a pre-commit hook and the build is now broken, **proceed to Task 6 immediately** to restore compilation. Otherwise commit succeeds and Task 6 picks up next.

---

### Task 6: NodeGraph codec

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `CodecRoundTripTest.kt`:

```kotlin
    @Test fun nodeGraphNbtRoundTrip() {
        val g = NodeGraph()
        g.add(Node(
            id = nodeA,
            typeKey = net.minecraft.resources.ResourceLocation("nodewire", "timer"),
            pos = CanvasPos(0f, 0f),
            inputs = emptyList(),
            outputs = listOf(Pin("out", "Pulse", PinType.BOOL)),
        ))
        g.add(Node(
            id = nodeB,
            typeKey = net.minecraft.resources.ResourceLocation("nodewire", "not"),
            pos = CanvasPos(50f, 0f),
            inputs = listOf(Pin("in", "In", PinType.BOOL)),
            outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        ))
        g.addEdge(Edge(PinRef(nodeA, "out"), PinRef(nodeB, "in")))

        val encoded = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, g).result().orElseThrow()
        val decoded = NodeGraph.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElseThrow()

        assertEquals(g.nodes.keys, decoded.nodes.keys)
        assertEquals(g.edges, decoded.edges)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: fail — `NodeGraph.CODEC` unresolved.

- [ ] **Step 3: Replace `NodeGraph.kt`**

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The mutable graph stored by `LogicBlockEntity`. Round-trips losslessly
 * through [CODEC].
 *
 * Nodes are stored as a flat list in the codec (each node carries its
 * own id); the in-memory representation is a map for O(1) lookup. The
 * codec rebuilds the map on parse.
 */
class NodeGraph {
    val nodes: MutableMap<NodeId, Node> = mutableMapOf()
    val edges: MutableList<Edge> = mutableListOf()

    fun add(node: Node) { nodes[node.id] = node }

    fun removeNode(id: NodeId) {
        nodes.remove(id) ?: return
        edges.removeAll { it.from.node == id || it.to.node == id }
    }

    fun addEdge(edge: Edge) { edges.add(edge) }

    fun removeEdge(edge: Edge) { edges.remove(edge) }

    /** Convenience: clear any existing edge into [to] before adding [edge]. */
    fun connectReplacing(edge: Edge) {
        edges.removeAll { it.to == edge.to }
        edges.add(edge)
    }

    companion object {
        val CODEC: Codec<NodeGraph> = RecordCodecBuilder.create { i ->
            i.group(
                Node.CODEC.listOf().fieldOf("nodes").forGetter { g -> g.nodes.values.toList() },
                Edge.CODEC.listOf().fieldOf("edges").forGetter { g -> g.edges.toList() },
            ).apply(i) { nodeList, edgeList ->
                NodeGraph().also { g ->
                    for (n in nodeList) g.nodes[n.id] = n
                    g.edges.addAll(edgeList)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: all 26 PASS.

Compilation may still fail in `LogicBlockEntity.kt` and `SaveGraphPacket.kt` — those still call `NodeGraph.toNbt`/`fromNbt`. We fix them in Tasks 8 and 9.

- [ ] **Step 5: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(graph): NodeGraph codec (drops toNbt/fromNbt)"
```

---

### Task 7: ChannelBinding + SideBinding codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CodecRoundTripTest.kt`:

```kotlin
    @Test fun channelBindingNbt() = roundTripNbt(
        dev.nitka.nodewire.block.ChannelBinding.CODEC,
        dev.nitka.nodewire.block.ChannelBinding(
            sourceChannelName = "speed",
            targetPos = net.minecraft.core.BlockPos(1, 2, 3),
            targetChannelName = "thrust",
        ),
    )

    @Test fun channelBindingSnbt() = roundTripSnbt(
        dev.nitka.nodewire.block.ChannelBinding.CODEC,
        dev.nitka.nodewire.block.ChannelBinding(
            sourceChannelName = "x",
            targetPos = net.minecraft.core.BlockPos(-1, -2, -3),
            targetChannelName = "y",
        ),
    )

    @Test fun sideBindingNbt() = roundTripNbt(
        dev.nitka.nodewire.block.SideBinding.CODEC,
        dev.nitka.nodewire.block.SideBinding(
            sourceChannelName = "latch",
            targetPos = net.minecraft.core.BlockPos(10, 20, 30),
            targetSide = net.minecraft.core.Direction.UP,
        ),
    )

    @Test fun sideBindingSnbt() = roundTripSnbt(
        dev.nitka.nodewire.block.SideBinding.CODEC,
        dev.nitka.nodewire.block.SideBinding(
            sourceChannelName = "fire",
            targetPos = net.minecraft.core.BlockPos.ZERO,
            targetSide = net.minecraft.core.Direction.NORTH,
        ),
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: 4 new tests fail.

- [ ] **Step 3: Replace `ChannelBinding.kt`**

```kotlin
package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos

/**
 * One cross-block channel link. A [LogicBlockEntity] keeps a list of these
 * on the **source** side; on each server tick it iterates them and pushes
 * the value of its [sourceChannelName] ChannelOutput into the target BE's
 * external-channel-input slot named [targetChannelName].
 */
data class ChannelBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetChannelName: String,
) {
    companion object {
        val CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter(ChannelBinding::targetPos),
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i, ::ChannelBinding)
        }
    }
}
```

- [ ] **Step 4: Replace `SideBinding.kt`**

```kotlin
package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Drive-by-wire binding from a named channel on a source LogicBlock to an
 * arbitrary block face. Distance-agnostic — signals reach the target via
 * the per-level VirtualSignalMap surfaced by [dev.nitka.nodewire.mixin.SignalGetterMixin].
 *
 * Channel value → redstone: BOOL → 0/15, INT → clamp(0..15), REDSTONE
 * pass-through. Other types contribute no signal at tick time.
 */
data class SideBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
) {
    companion object {
        val CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter(SideBinding::targetPos),
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
            ).apply(i, ::SideBinding)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: all 30 PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/block/ChannelBinding.kt \
        src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt
git commit -m "feat(block): ChannelBinding + SideBinding codecs"
```

---

### Task 8: Migrate `LogicBlockEntity` to codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

After this task `compileKotlin` should pass again — Tasks 5–7 left
LogicBlockEntity and SaveGraphPacket broken because they referenced
deleted `toNbt`/`fromNbt`. Task 8 fixes LogicBlockEntity; Task 9 fixes
SaveGraphPacket.

- [ ] **Step 1: Replace `saveAdditional`**

Find this block in `LogicBlockEntity.kt`:

```kotlin
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put("graph", graph.toNbt())
        if (bindings.isNotEmpty()) {
            val list = ListTag()
            for (b in bindings) list.add(b.toNbt())
            tag.put("bindings", list)
        }
        if (sideBindings.isNotEmpty()) {
            val list = ListTag()
            for (b in sideBindings) list.add(b.toNbt())
            tag.put("side_bindings", list)
        }
    }
```

Replace with:

```kotlin
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put(
            "graph",
            dev.nitka.nodewire.graph.NodeGraph.CODEC
                .encodeStart(NbtOps.INSTANCE, graph).result()
                .orElseThrow { IllegalStateException("graph encode failed") },
        )
        if (bindings.isNotEmpty()) {
            tag.put(
                "bindings",
                ChannelBinding.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, bindings.toList()).result()
                    .orElseThrow { IllegalStateException("bindings encode failed") },
            )
        }
        if (sideBindings.isNotEmpty()) {
            tag.put(
                "side_bindings",
                SideBinding.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, sideBindings.toList()).result()
                    .orElseThrow { IllegalStateException("side_bindings encode failed") },
            )
        }
    }
```

Add this import at top of the file (alongside existing nbt imports):

```kotlin
import net.minecraft.nbt.NbtOps
```

You can drop `import net.minecraft.nbt.ListTag` if no other code in the file uses it after this change. Grep first:

```bash
cd /home/nitka/CODING/nodewire && grep -n "ListTag" src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
```

If the only hit is the import line, remove it.

- [ ] **Step 2: Replace `load`**

Find this block:

```kotlin
    override fun load(tag: CompoundTag) {
        super.load(tag)
        graph = if (tag.contains("graph")) {
            NodeGraph.fromNbt(tag.getCompound("graph"))
        } else {
            NodeGraph()
        }
        bindings.clear()
        if (tag.contains("bindings")) {
            val list = tag.getList("bindings", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) bindings.add(ChannelBinding.fromNbt(list.getCompound(i)))
        }
        sideBindings.clear()
        if (tag.contains("side_bindings")) {
            val list = tag.getList("side_bindings", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) sideBindings.add(SideBinding.fromNbt(list.getCompound(i)))
        }
        invalidateEvaluator()
    }
```

Replace with:

```kotlin
    override fun load(tag: CompoundTag) {
        super.load(tag)
        graph = if (tag.contains("graph")) {
            dev.nitka.nodewire.graph.NodeGraph.CODEC
                .parse(NbtOps.INSTANCE, tag.getCompound("graph")).result()
                .orElse(dev.nitka.nodewire.graph.NodeGraph())
        } else {
            dev.nitka.nodewire.graph.NodeGraph()
        }
        bindings.clear()
        if (tag.contains("bindings")) {
            val list = ChannelBinding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get("bindings")).result()
                .orElse(emptyList())
            bindings.addAll(list)
        }
        sideBindings.clear()
        if (tag.contains("side_bindings")) {
            val list = SideBinding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get("side_bindings")).result()
                .orElse(emptyList())
            sideBindings.addAll(list)
        }
        invalidateEvaluator()
    }
```

You can drop `import net.minecraft.nbt.Tag` if no other line in the file uses it. Grep again:

```bash
cd /home/nitka/CODING/nodewire && grep -n "\bTag\b" src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
```

Only the import line → remove.

- [ ] **Step 3: Compile**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: most files OK, but `SaveGraphPacket.kt` still fails (it calls `NodeGraph.fromNbt`). That's expected — Task 9 fixes it.

- [ ] **Step 4: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "refactor(block): LogicBlockEntity save/load via codecs"
```

If your environment has a pre-commit `./gradlew compileKotlin` hook that blocks on the SaveGraphPacket error, **proceed immediately to Task 9** — the two changes are coupled and need to land together. Use `git commit --no-verify` only as last resort with user awareness.

---

### Task 9: Migrate `SaveGraphPacket` to codec

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/SaveGraphPacket.kt`

The packet currently carries `graphTag: CompoundTag`. After migration it carries `graph: NodeGraph` directly and encodes via `FriendlyByteBuf.writeCodec` (Task 1).

- [ ] **Step 1: Add packet CODEC + change constructor field**

Replace the class declaration:

```kotlin
class SaveGraphPacket(val pos: BlockPos, val graphTag: CompoundTag) {
```

with:

```kotlin
class SaveGraphPacket(val pos: BlockPos, val graph: NodeGraph) {
```

- [ ] **Step 2: Replace `encode` and `decode`**

Replace this:

```kotlin
    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeNbt(graphTag)
    }
```

with:

```kotlin
    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(CODEC, this)
    }
```

Replace the existing `decode` in the companion:

```kotlin
        fun decode(buf: FriendlyByteBuf): SaveGraphPacket =
            SaveGraphPacket(buf.readBlockPos(), buf.readNbt() ?: CompoundTag())
```

with:

```kotlin
        val CODEC: com.mojang.serialization.Codec<SaveGraphPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(SaveGraphPacket::pos),
                    dev.nitka.nodewire.graph.NodeGraph.CODEC.fieldOf("graph").forGetter(SaveGraphPacket::graph),
                ).apply(i, ::SaveGraphPacket)
            }

        fun decode(buf: FriendlyByteBuf): SaveGraphPacket = buf.readCodec(CODEC)
```

- [ ] **Step 3: Update `handle()` to use `this.graph` directly**

Find this block in `handle()`:

```kotlin
            val graph = try {
                NodeGraph.fromNbt(graphTag)
            } catch (t: Throwable) {
                LOG.warn("Rejecting SaveGraphPacket: malformed NBT — ${t.message}")
                return@enqueueWork
            }
```

Replace with:

```kotlin
            // Codec already parsed the graph during decode; if parsing
            // failed the packet wouldn't have been constructed. Reach for
            // it directly.
            val graph = this.graph
```

- [ ] **Step 4: Add imports + remove unused ones**

Add at top of file:

```kotlin
import dev.nitka.nodewire.net.readCodec
import dev.nitka.nodewire.net.writeCodec
```

(They're in the same package, but explicit is fine; Kotlin will resolve.)

Remove `import net.minecraft.nbt.CompoundTag` if no other code in the file uses it. Grep:

```bash
cd /home/nitka/CODING/nodewire && grep -n "CompoundTag" src/main/kotlin/dev/nitka/nodewire/net/SaveGraphPacket.kt
```

If only the import remains → remove.

- [ ] **Step 5: Update test `SaveGraphPacketTest.kt`**

Open `src/test/kotlin/dev/nitka/nodewire/net/SaveGraphPacketTest.kt`. The test currently builds a `CompoundTag` and passes it via the old constructor. Replace any `SaveGraphPacket(pos, tag)` call with `SaveGraphPacket(pos, graph)` where `graph` is a `NodeGraph` built inline. The validation logic in `validate(graph)` is unchanged, so the test's behaviour should stay the same once it constructs from a NodeGraph directly.

If the test was checking byte-for-byte encode/decode through a `FriendlyByteBuf`, keep that — just use `NodeGraph` instead of `CompoundTag` at the construction sites and `assertEquals` on something derived from the round-tripped graph (e.g., `decoded.graph.nodes.size`) rather than tag identity.

Concretely: read the existing test file first (`cat` it), then adapt each assertion to use the new field. If you cannot adapt cleanly within ~30 minutes, mark this step as DONE_WITH_CONCERNS and ask for guidance.

- [ ] **Step 6: Compile + run tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test --tests "dev.nitka.nodewire.net.SaveGraphPacketTest" --tests "dev.nitka.nodewire.graph.CodecRoundTripTest"`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/net/SaveGraphPacket.kt \
        src/test/kotlin/dev/nitka/nodewire/net/SaveGraphPacketTest.kt
git commit -m "refactor(net): SaveGraphPacket via codec — carries NodeGraph"
```

---

### Task 10: Migrate `BindChannelPacket` and `BindSideChannelPacket` to codecs

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/BindChannelPacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/BindSideChannelPacket.kt`

Both packets are plain records of `BlockPos + String + BlockPos + (String|Direction)`. Pattern is identical.

- [ ] **Step 1: Add CODEC and rewrite encode/decode in `BindChannelPacket`**

Replace its companion's `decode` with:

```kotlin
    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        // Generous reach — link tool can be used while peeking around.
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val CODEC: com.mojang.serialization.Codec<BindChannelPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(BindChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindChannelPacket::sourceChannelName),
                    net.minecraft.core.BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindChannelPacket::targetPos),
                    com.mojang.serialization.Codec.STRING.fieldOf("tgt_ch").forGetter(BindChannelPacket::targetChannelName),
                ).apply(i, ::BindChannelPacket)
            }

        fun decode(buf: FriendlyByteBuf): BindChannelPacket = buf.readCodec(CODEC)
    }
```

Replace its `encode`:

```kotlin
    fun encode(buf: FriendlyByteBuf) { buf.writeCodec(CODEC, this) }
```

- [ ] **Step 2: Add CODEC and rewrite encode/decode in `BindSideChannelPacket`**

Replace its companion's `decode` with:

```kotlin
    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val CODEC: com.mojang.serialization.Codec<BindSideChannelPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(BindSideChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindSideChannelPacket::sourceChannelName),
                    net.minecraft.core.BlockPos.CODEC.fieldOf("tgt_pos").forGetter(BindSideChannelPacket::targetPos),
                    net.minecraft.core.Direction.CODEC.fieldOf("tgt_side").forGetter(BindSideChannelPacket::targetSide),
                ).apply(i, ::BindSideChannelPacket)
            }

        fun decode(buf: FriendlyByteBuf): BindSideChannelPacket = buf.readCodec(CODEC)
    }
```

Replace its `encode`:

```kotlin
    fun encode(buf: FriendlyByteBuf) { buf.writeCodec(CODEC, this) }
```

- [ ] **Step 3: Compile**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/net/BindChannelPacket.kt \
        src/main/kotlin/dev/nitka/nodewire/net/BindSideChannelPacket.kt
git commit -m "refactor(net): bind packets via codec"
```

---

### Task 11: Migrate `RemoveBindingPacket` + `Kind` codec

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt`

The `Kind` enum needs its own codec; the packet codec references it.

- [ ] **Step 1: Add Kind.CODEC and rewrite packet encode/decode**

Replace the `companion object` in `RemoveBindingPacket.kt` with:

```kotlin
    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 32.0 * 32.0

        private val KIND_CODEC: com.mojang.serialization.Codec<Kind> =
            com.mojang.serialization.Codec.STRING.xmap(Kind::valueOf, Kind::name)

        val CODEC: com.mojang.serialization.Codec<RemoveBindingPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(RemoveBindingPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(RemoveBindingPacket::sourceChannelName),
                    net.minecraft.core.BlockPos.CODEC.fieldOf("tgt_pos").forGetter(RemoveBindingPacket::targetPos),
                    KIND_CODEC.fieldOf("kind").forGetter(RemoveBindingPacket::kind),
                    com.mojang.serialization.Codec.STRING.fieldOf("extra").forGetter(RemoveBindingPacket::extra),
                ).apply(i, ::RemoveBindingPacket)
            }

        fun decode(buf: FriendlyByteBuf): RemoveBindingPacket = buf.readCodec(CODEC)
    }
```

Replace its `encode`:

```kotlin
    fun encode(buf: FriendlyByteBuf) { buf.writeCodec(CODEC, this) }
```

- [ ] **Step 2: Compile**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt
git commit -m "refactor(net): RemoveBindingPacket + Kind via codec"
```

---

### Task 12: Update legacy `NodeGraphNbtTest` to codec

**Files:**
- Modify: `src/test/kotlin/dev/nitka/nodewire/graph/NodeGraphNbtTest.kt`

The legacy test almost certainly calls `graph.toNbt()` / `NodeGraph.fromNbt(...)` which now don't exist. Its intent — verifying NodeGraph round-trip — is fully covered by `CodecRoundTripTest.nodeGraphNbtRoundTrip`. Delete or rewrite.

- [ ] **Step 1: Read the test file**

Run: `cat /home/nitka/CODING/nodewire/src/test/kotlin/dev/nitka/nodewire/graph/NodeGraphNbtTest.kt`

If every assertion in the file is "encode → decode → equality" on `NodeGraph` (or sub-types), the test is redundant with `CodecRoundTripTest`. Delete the file:

```bash
cd /home/nitka/CODING/nodewire
git rm src/test/kotlin/dev/nitka/nodewire/graph/NodeGraphNbtTest.kt
```

If any assertion is **structural** (specific NBT key names, exact integer values in the encoded tag, etc.) that the codec might silently change, rewrite the test using `NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).result().orElseThrow()` to get the tag, then assert against that tag.

- [ ] **Step 2: Compile + run all tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
cd /home/nitka/CODING/nodewire
git add -A src/test/kotlin/dev/nitka/nodewire/graph/
git commit -m "test: retire NodeGraphNbtTest in favour of codec round-trip"
```

---

### Task 13: Full build verification

**Files:** none.

- [ ] **Step 1: Clean build + tests**

Run: `cd /home/nitka/CODING/nodewire && ./gradlew build`
Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 2: Sanity-grep for residual legacy methods**

Run:

```bash
cd /home/nitka/CODING/nodewire
grep -rn "\.toNbt()\|\.fromNbt(" src/main/kotlin/dev/nitka/nodewire/ | \
    grep -vE "graph\.toNbt\b|graph\.fromNbt\b" || true
```

Expected: empty output (or only hits that reference the `graph: CompoundTag` field on `Node`, which is a config tag, not a method call). If anything else is left over, the migration missed a spot.

- [ ] **Step 3: Hand off to user**

Stop here. Tell the user:

> "Codec migration complete. Existing logic-block saves WILL fail to load (clean-slate per spec). Open the client, place a fresh logic block, build a graph + bindings, reload — confirm everything round-trips through the new codecs."

No commit on this step.

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|------------------|------|
| `PinType.CODEC` | 2 |
| `Pin.CODEC` | 2 |
| `PinValue.CODEC` (sealed dispatch) | 3 |
| `PinRef.CODEC` | 4 |
| `Edge.CODEC` | 4 |
| `CanvasPos.CODEC` | 5 |
| `Node.CODEC` | 5 |
| `NodeGraph.CODEC` | 6 |
| `ChannelBinding.CODEC` | 7 |
| `SideBinding.CODEC` | 7 |
| `LogicBlockEntity` save/load via codec | 8 |
| `SaveGraphPacket.CODEC` + `writeWithCodec`-style | 9 |
| `BindChannelPacket.CODEC` | 10 |
| `BindSideChannelPacket.CODEC` | 10 |
| `RemoveBindingPacket.CODEC` + `Kind.CODEC` | 11 |
| NBT round-trip tests | 2, 3, 4, 5, 6, 7 |
| SNBT round-trip tests | 2, 3, 4, 5, 6, 7 |
| Clean-slate migration (no fallback) | implicit in 8 — `load` uses `.orElse(NodeGraph())` so a corrupt old save just resets the BE, not crashes |
| Single serialization layer for save and wire | yes — `CodecBufExtensions` (Task 1) is the only bridge; all packets and BE share it |

**Placeholder scan:** none. Each step has either runnable code or an exact command + expected output.

**Type consistency:** `writeCodec(codec, this)` and `readCodec(codec): T` signatures used identically in Tasks 9, 10, 11. `Node.CODEC`, `NodeGraph.CODEC`, etc. names match across tasks. `KIND_CODEC` (private) and `CODEC` (public) names consistent.

**One known wobble:** Tasks 5–7 leave `LogicBlockEntity` and `SaveGraphPacket` momentarily uncompilable because they delete `Node.toNbt`/`NodeGraph.fromNbt` etc. before the call sites migrate. Tasks 8–9 fix it. The plan flags this explicitly at the relevant `commit` steps; if a pre-commit hook blocks, the implementer is instructed to proceed to the fix task.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-14-codec-serialization.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks; ideal because tasks 2–7 are mechanical and parallel-shaped.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

**Which approach?**
