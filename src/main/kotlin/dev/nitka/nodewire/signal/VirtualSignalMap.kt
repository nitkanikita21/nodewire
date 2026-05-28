package dev.nitka.nodewire.signal

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import java.util.WeakHashMap

/**
 * Per-level map of virtual redstone signals injected by [dev.nitka.nodewire.block.LogicBlockEntity]
 * side-bindings. Consulted by [dev.nitka.nodewire.signal.LevelSignalAccess] (a Mixin into vanilla
 * `Level.getBestNeighborSignal`) so any block that polls "am I powered?" sees these injected
 * values regardless of how far the source is.
 *
 * Storage: `targetPos → (targetSide → power)`. A side may receive multiple
 * source contributions in one tick — we store per-source values keyed by
 * source pos so a binding can be cleared in isolation when its source is
 * removed.
 *
 * Threading: only mutated from the server thread (logic block tick + BE
 * remove). Reads happen on the server thread too (redstone polls). One
 * `WeakHashMap<Level, MapData>` keyed by Level instance, weak so removed
 * worlds don't leak.
 */
object VirtualSignalMap {

    private val byLevel = WeakHashMap<Level, MapData>()

    fun of(level: Level): MapData = byLevel.getOrPut(level) { MapData() }

    class MapData {
        // (targetPos, targetSide) → (sourcePos → power)
        private val signals = HashMap<Key, HashMap<BlockPos, Int>>()
        // Index from sourcePos → set of Keys it contributes to, for fast
        // clearSource without scanning every entry.
        private val bySource = HashMap<BlockPos, MutableSet<Key>>()

        fun put(sourcePos: BlockPos, targetPos: BlockPos, targetSide: Direction, power: Int) {
            val k = Key(targetPos, targetSide)
            val perSource = signals.getOrPut(k) { HashMap() }
            if (power <= 0) {
                perSource.remove(sourcePos)
                if (perSource.isEmpty()) signals.remove(k)
                bySource[sourcePos]?.remove(k)
            } else {
                perSource[sourcePos] = power
                bySource.getOrPut(sourcePos) { HashSet() }.add(k)
            }
        }

        /** Drop every signal contributed by [sourcePos]. */
        fun clearSource(sourcePos: BlockPos) {
            val keys = bySource.remove(sourcePos) ?: return
            for (k in keys) {
                val perSource = signals[k] ?: continue
                perSource.remove(sourcePos)
                if (perSource.isEmpty()) signals.remove(k)
            }
        }

        /**
         * Strongest virtual signal reaching [targetPos] specifically on
         * [face]. Used by the `getSignal(pos, dir)` mixin so blocks that
         * inspect a SINGLE incoming face (e.g. Simulated's Directional
         * Gearshift checking left vs right) see our injection — the
         * any-face [strongestAt] alone isn't enough for them.
         */
        fun powerAtFace(targetPos: BlockPos, face: Direction): Int {
            val perSource = signals[Key(targetPos, face)] ?: return 0
            var best = 0
            for (v in perSource.values) {
                if (v > best) best = v
                if (best >= 15) return 15
            }
            return best
        }

        /**
         * Strongest virtual signal reaching [targetPos] from any face,
         * mirroring what vanilla's [net.minecraft.world.level.Level.getBestNeighborSignal]
         * would return when scanning the six neighbours. Returns 0 if no
         * injected signal exists for this position.
         */
        fun strongestAt(targetPos: BlockPos): Int {
            var best = 0
            for (face in Direction.values()) {
                val perSource = signals[Key(targetPos, face)] ?: continue
                for (v in perSource.values) {
                    if (v > best) best = v
                    if (best >= 15) return 15
                }
            }
            return best
        }
    }

    private data class Key(val pos: BlockPos, val face: Direction)
}
