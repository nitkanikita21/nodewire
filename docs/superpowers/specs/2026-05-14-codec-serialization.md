# Codec-based serialization

## Context

The graph model + cross-block bindings currently serialize via hand-written
`toNbt() / fromNbt()` methods on each class:

- `NodeGraph.{toNbt, fromNbt}` (nodes + edges)
- `Node.{toNbt, fromNbt}` (id, typeKey, config, position)
- `Edge.{toNbt, fromNbt}` + `PinRef.{toNbt, fromNbt}`
- `PinValue.toNbt` + `PinValue.Companion.fromNbt` (sealed dispatch via a
  `type` discriminator string)
- `ChannelBinding`, `SideBinding`

The same shape is also wire-encoded in `SaveGraphPacket`, `BindChannelPacket`,
`BindSideChannelPacket`, `RemoveBindingPacket` via hand-written
`FriendlyByteBuf` reads/writes. This spec puts those on codecs too —
`FriendlyByteBuf.writeWithCodec(codec, value)` / `readWithCodec(codec)`
serializes the value to NBT through the codec then ships the tag in the
buffer, giving us one serialization layer for save *and* wire.

This spec moves all NBT serialization to MC's `com.mojang.serialization.Codec`
infrastructure: one canonical `CODEC` per type, NBT via `NbtOps.INSTANCE`,
SNBT round-trip "for free" via `NbtUtils.structureToSnbt` + `TagParser`.

User opted for **clean-slate** migration — no read-fallback to the legacy
format. Existing worlds with saved logic-block NBT will break on load; the
user accepts that.

## Goals

- One `Codec<T>` per serializable type, defined in that type's companion
  object as `val CODEC: Codec<T>`.
- `LogicBlockEntity.saveAdditional / load` encode/decode through codecs +
  `NbtOps`.
- Removal of every hand-written `toNbt / fromNbt` method on the affected
  types.
- Unit tests: round-trip each codec through both NBT (`Tag`) and SNBT
  (`String`) and confirm equality.
- No behavioural change otherwise — the editor, eval, networking all keep
  working exactly as today, just reading/writing via codecs.

## Non-goals

- A user-facing SNBT export action. Codecs *enable* it cheaply, but the
  export UI is a separate spec.
- Backward compatibility with old NBT. Clean slate.
- Schema versioning or migration framework. The codec for each type is
  the schema; future versions get their own spec.

## Affected types

| Type | Codec location | Fields |
|------|----------------|--------|
| `PinType` | `PinType.kt` companion | enum, encoded as lowercase name string |
| `PinValue` (sealed) | `PinValue.kt` companion | dispatch on `type` discriminator |
| `PinRef` | `PinRef.kt` companion | `node: UUID`, `pin: String` |
| `Edge` | `Edge.kt` companion | `from: PinRef`, `to: PinRef` |
| `Node` | `Node.kt` companion | `id: UUID`, `typeKey: ResourceLocation`, `config: CompoundTag`, `position: Vec2` |
| `NodeGraph` | `NodeGraph.kt` companion | nodes (map), edges (list) |
| `ChannelBinding` | `ChannelBinding.kt` companion | source name, target pos, target name |
| `SideBinding` | `SideBinding.kt` companion | source name, target pos, target side |
| `SaveGraphPacket` | `SaveGraphPacket.kt` companion | `pos: BlockPos`, `graph: NodeGraph` |
| `BindChannelPacket` | `BindChannelPacket.kt` companion | 4 fields per current shape |
| `BindSideChannelPacket` | `BindSideChannelPacket.kt` companion | 4 fields per current shape |
| `RemoveBindingPacket` | `RemoveBindingPacket.kt` companion | 5 fields incl. `Kind` enum |
| `RemoveBindingPacket.Kind` | inner companion | enum codec via `StringRepresentable` or `Codec.STRING.xmap` |

### Building blocks

- `UUID` → `com.mojang.serialization.codecs.PrimitiveCodec.STRING` wrapped:
  `Codec.STRING.xmap(UUID::fromString, UUID::toString)`.
- `ResourceLocation` → `ResourceLocation.CODEC`.
- `BlockPos` → `BlockPos.CODEC`.
- `Direction` → `Direction.CODEC` (enum codec built into vanilla).
- `CompoundTag` → `CompoundTag.CODEC` (also vanilla in 1.20.1).
- `Vec2`-ish position (graph node screen coords) → `RecordCodecBuilder` of
  two floats.

### `PinValue` sealed dispatch

Cleanest pattern in DFU/Codec land:

```kotlin
val CODEC: Codec<PinValue> = Codec.STRING.dispatch(
    { pv -> typeKey(pv) },
    { key -> codecFor(key) },
)
```

Where `typeKey(pv)` returns `"bool"|"int"|"float"|"redstone"|"str"|"vec2"|"vec3"|"quat"`
and `codecFor(key)` returns the per-variant codec wrapped into `Codec<PinValue>`
(each variant has a single inline field).

### `NodeGraph` shape

Nodes are currently a `Map<UUID, Node>`. Codec: encode as a `List<Node>`
(each carries its own `id`) — the map is reconstructed on parse by
indexing on `Node.id`. Drops the redundant outer key.

Edges stay a `List<Edge>`.

## Call sites updated

- `LogicBlockEntity.saveAdditional(tag)`:
  - `tag.put("graph", NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).result().orElseThrow())`
  - Same shape for `bindings` (list) and `sideBindings` (list).
- `LogicBlockEntity.load(tag)`:
  - `NodeGraph.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("graph")).result().orElse(NodeGraph())`
  - Same for binding lists.
- All four packets get a top-level `CODEC: Codec<T>` and their
  `encode/decode` collapse to one-liners:

  ```kotlin
  fun encode(buf: FriendlyByteBuf) { buf.writeWithCodec(NbtOps.INSTANCE, CODEC, this) }
  companion object {
      val CODEC: Codec<MyPacket> = RecordCodecBuilder.create { it.group(...).apply(it, ::MyPacket) }
      fun decode(buf: FriendlyByteBuf): MyPacket = buf.readWithCodec(NbtOps.INSTANCE, CODEC, MAX_NBT_BYTES)
  }
  ```

  `MAX_NBT_BYTES` is a project-wide constant (e.g. 2 MiB) to cap the
  decoded payload — vanilla `readWithCodec` requires a size limit.

  `handle()` bodies are unchanged — they operate on the already-decoded
  instance.

## Testing

For every codec, two round-trip tests in
`src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`:

1. **NBT round-trip**: instance → `encodeStart(NbtOps)` → `parse(NbtOps)` → asserts deep equality.
2. **SNBT round-trip**: instance → encode → `NbtUtils.structureToSnbt` → `TagParser.parseTag` → decode → equality.

Plus one integration test: build a small NodeGraph with 2 nodes + 1 edge,
encode + decode, assert structure preserved.

Tests must not require a running MC client — only the deserialization
classes (`Tag`, `NbtOps`, `Codec`) are needed, all already on the test
classpath via the existing config that extends `testCompileClasspath` from
the main classpath.

## File structure

- Modify (add `val CODEC` companion, delete old `toNbt/fromNbt`):
  - `graph/PinType.kt`
  - `graph/PinValue.kt`
  - `graph/PinRef.kt`
  - `graph/Edge.kt`
  - `graph/Node.kt`
  - `graph/NodeGraph.kt`
  - `block/ChannelBinding.kt`
  - `block/SideBinding.kt`
- Modify (use codec at call sites + their own packet `CODEC`):
  - `block/LogicBlockEntity.kt`
  - `net/SaveGraphPacket.kt`
  - `net/BindChannelPacket.kt`
  - `net/BindSideChannelPacket.kt`
  - `net/RemoveBindingPacket.kt`
- Create:
  - `src/test/kotlin/dev/nitka/nodewire/graph/CodecRoundTripTest.kt`
- Delete: any test that explicitly tests `toNbt/fromNbt` of the now-codec
  types (those tests are subsumed by the round-trip tests). Notably
  `NodeGraphNbtTest.kt` — replace its assertions with codec-based ones.

## Out of scope (future)

- SNBT user-facing export action in the editor context menu — separate
  spec. Codec refactor unblocks it.
- Schema versioning (e.g., `version: 1` discriminator). When we change a
  type's shape, we'll spec a versioned codec at that point.
- Codec-driven defaulting (`Codec.optionalFieldOf`) for forward-compat
  defaults. The current model is "all fields required"; revisit when
  needed.
