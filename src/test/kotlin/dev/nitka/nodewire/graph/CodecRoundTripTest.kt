package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CodecRoundTripTest {

    companion object {
        @BeforeAll @JvmStatic fun registerTypes() {
            StockNodeTypes.registerAll()
        }
    }

    @BeforeEach fun resetBackends() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(WorldBackend)
    }

    /**
     * Build a Node whose pin shape matches what the registry would derive
     * on decode. Since [Node.CODEC] no longer serialises pin lists, in-test
     * constructions that supplied custom pin shapes wouldn't round-trip.
     */
    private fun rl(path: String) =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodewire", path)

    private fun mkNode(
        typeKey: net.minecraft.resources.ResourceLocation,
        id: NodeId,
        pos: CanvasPos,
        config: net.minecraft.nbt.CompoundTag,
        label: String? = null,
    ): Node {
        val t = NodeTypeRegistry.get(typeKey)
            ?: error("Type not registered: $typeKey")
        val (ins, outs) = t.pinsFor(config)
        return Node(id, typeKey, pos, ins, outs, config, label)
    }

    /** Encode → decode through NbtOps. Asserts result equals input. */
    private fun <T> roundTripNbt(codec: Codec<T>, value: T) {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result().orElseThrow()
        val decoded = codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow()
        assertEquals(value, decoded)
    }

    /** Encode → SNBT → parse → decode. Asserts result equals input. */
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

    private val nodeA: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val nodeB: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test fun pinRefNbt()   = roundTripNbt(PinRef.CODEC, PinRef(nodeA, "out"))
    @Test fun pinRefSnbt()  = roundTripSnbt(PinRef.CODEC, PinRef(nodeA, "out"))
    @Test fun edgeNbt()     = roundTripNbt(Edge.CODEC, Edge(PinRef(nodeA, "out"), PinRef(nodeB, "in")))
    @Test fun edgeSnbt()    = roundTripSnbt(Edge.CODEC, Edge(PinRef(nodeA, "out"), PinRef(nodeB, "in")))

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

    @Test fun canvasPosNbt() = roundTripNbt(CanvasPos.CODEC, CanvasPos(1.5f, -2.5f))
    @Test fun canvasPosSnbt() = roundTripSnbt(CanvasPos.CODEC, CanvasPos(0f, 0f))

    @Test fun nodeNbt() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putInt("period", 20) }
        roundTripNbt(Node.CODEC, mkNode(rl("timer"), nodeA, CanvasPos(10f, 20f), cfg))
    }

    @Test fun nodeSnbt() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putString("name", "speed"); putString("type", "INT") }
        roundTripSnbt(Node.CODEC, mkNode(rl("channel_output"), nodeB, CanvasPos(-50f, 5f), cfg))
    }

    @Test fun nodeGraphNbtRoundTrip() {
        val g = NodeGraph()
        g.add(mkNode(rl("timer"), nodeA, CanvasPos(0f, 0f), net.minecraft.nbt.CompoundTag()))
        // The "not" type isn't registered, so its decode produces empty pins.
        // We still want the graph-codec roundtrip to preserve the entry — only
        // ids/edges are asserted below.
        g.add(Node(
            id = nodeB,
            typeKey = rl("not"),
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

    // --- Consolidated node codec roundtrips (Phase 6) ---

    private val nodeC: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003")

    @Test fun constantStringRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply {
            putString("type", "STRING")
            putBoolean("bool", false)
            putInt("int", 0)
            putFloat("float", 0f)
            putString("string", "hello")
            putFloat("x", 0f); putFloat("y", 0f); putFloat("z", 0f)
        }
        roundTripNbt(Node.CODEC, mkNode(rl("constant"), nodeC, CanvasPos(0f, 0f), cfg))
    }

    @Test fun logicGateXorRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putString("op", "XOR") }
        roundTripNbt(Node.CODEC, mkNode(rl("logic_gate"), nodeC, CanvasPos(10f, 10f), cfg))
    }

    @Test fun mathDivFloatRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply {
            putString("op", "DIV")
            putString("type", "FLOAT")
        }
        roundTripNbt(Node.CODEC, mkNode(rl("math"), nodeC, CanvasPos(20f, 0f), cfg))
    }

    @Test fun compareFloatRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply { putString("type", "FLOAT") }
        roundTripNbt(Node.CODEC, mkNode(rl("compare"), nodeC, CanvasPos(30f, 0f), cfg))
    }

    @Test fun convertBoolToIntRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply {
            putString("sourceType", "BOOL")
            putString("targetType", "INT")
        }
        val n = Node(
            id = nodeC,
            typeKey = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("nodewire", "convert"),
            pos = CanvasPos(40f, 0f),
            inputs = listOf(Pin("in", "In", PinType.BOOL)),
            outputs = listOf(Pin("out", "Out", PinType.INT)),
            config = cfg,
        )
        roundTripNbt(Node.CODEC, n)
    }

    @Test fun convertIntToRedstoneScaledRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply {
            putString("sourceType", "INT")
            putString("targetType", "REDSTONE")
            putString("mode", "scaled")
            putInt("min", 0)
            putInt("max", 100)
        }
        roundTripNbt(Node.CODEC, mkNode(rl("convert"), nodeC, CanvasPos(50f, 0f), cfg))
    }

    @Test fun convertRedstoneToFloatNormalizedRoundTrip() {
        val cfg = net.minecraft.nbt.CompoundTag().apply {
            putString("sourceType", "REDSTONE")
            putString("targetType", "FLOAT")
            putString("mode", "normalized")
        }
        roundTripNbt(Node.CODEC, mkNode(rl("convert"), nodeC, CanvasPos(60f, 0f), cfg))
    }

    @Test fun channelBindingNbt() = roundTripNbt(
        dev.nitka.nodewire.block.ChannelBinding.CODEC,
        dev.nitka.nodewire.block.ChannelBinding(
            sourceChannelName = "speed",
            target = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(1, 2, 3))),
            targetChannelName = "thrust",
        ),
    )

    @Test fun channelBindingSnbt() = roundTripSnbt(
        dev.nitka.nodewire.block.ChannelBinding.CODEC,
        dev.nitka.nodewire.block.ChannelBinding(
            sourceChannelName = "x",
            target = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(-1, -2, -3))),
            targetChannelName = "y",
        ),
    )

    @Test fun sideBindingNbt() = roundTripNbt(
        dev.nitka.nodewire.block.SideBinding.CODEC,
        dev.nitka.nodewire.block.SideBinding(
            sourceChannelName = "latch",
            target = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(10, 20, 30))),
            targetSide = net.minecraft.core.Direction.UP,
        ),
    )

    @Test fun sideBindingSnbt() = roundTripSnbt(
        dev.nitka.nodewire.block.SideBinding.CODEC,
        dev.nitka.nodewire.block.SideBinding(
            sourceChannelName = "fire",
            target = EndpointRef(WorldBackend.id, WorldPayload(BlockPos.ZERO)),
            targetSide = net.minecraft.core.Direction.NORTH,
        ),
    )

    @Test fun nodeWithLabelNbt() {
        val n = mkNode(rl("logic_gate"), Node.newId(), CanvasPos(0f, 0f), net.minecraft.nbt.CompoundTag(), label = "My Door Counter")
        roundTripNbt(Node.CODEC, n)
    }

    @Test fun nodeWithNullLabelNbt() {
        val n = mkNode(rl("logic_gate"), Node.newId(), CanvasPos(0f, 0f), net.minecraft.nbt.CompoundTag())
        assertEquals(null, n.label)
        roundTripNbt(Node.CODEC, n)
    }

    @Test fun nodeLegacyWithoutLabelFieldDecodesToNull() {
        val tag = net.minecraft.nbt.CompoundTag().apply {
            // UUID_CODEC serialises as a plain string, not putUUID's two-long encoding
            putString("id", java.util.UUID.randomUUID().toString())
            putString("type", "nodewire:logic_gate")
            put("pos", net.minecraft.nbt.CompoundTag().apply {
                putFloat("x", 0f); putFloat("y", 0f)
            })
            put("inputs", net.minecraft.nbt.ListTag())
            put("outputs", net.minecraft.nbt.ListTag())
            put("config", net.minecraft.nbt.CompoundTag())
        }
        val decoded = Node.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(null, decoded.label)
    }
}
