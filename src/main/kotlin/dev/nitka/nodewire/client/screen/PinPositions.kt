package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.mutableStateMapOf
import dev.nitka.nodewire.graph.NodeId

/**
 * Direction of a pin within its node's [Node.inputs] / `outputs` list. Used
 * as part of [PinKey] so a single pin id can address either an input or an
 * output unambiguously (technically the spec keeps input/output IDs unique
 * per side, but encoding the direction makes lookup cheap and removes any
 * room for accidental collisions).
 */
enum class PinSide { Input, Output }

data class PinKey(val node: NodeId, val pin: String, val side: PinSide)

/**
 * Position of a pin handle's centre in node-canvas world coordinates. The
 * wire renderer reads this map to know where each edge endpoint sits;
 * [NodeCard] writes it from `.onPositioned` on each handle so the values
 * stay fresh as cards are dragged.
 *
 * Backed by [mutableStateMapOf] so the wire renderer recomposes whenever
 * any handle moves.
 */
class PinPositions {
    private val map = mutableStateMapOf<PinKey, Pair<Float, Float>>()

    fun set(key: PinKey, x: Float, y: Float) {
        map[key] = x to y
    }

    fun get(key: PinKey): Pair<Float, Float>? = map[key]

    /** Snapshot for the renderer — iterating the live map under recomposition is fine. */
    fun all(): Map<PinKey, Pair<Float, Float>> = map
}

