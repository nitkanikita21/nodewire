package dev.nitka.nodewire.integration.tracksplus

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.fml.ModList
import kotlin.math.roundToInt

/**
 * Create: Tracks+ (`tracks`) tuning gateway for the Track Tuning Key.
 *
 * Reflection over `SableTrackBlockEntity`'s PUBLIC tuning API — no
 * compile-time dep (the mod publishes no maven artifact):
 *  * `getTuning(String): double` — current value of a named tuning knob.
 *  * `adjustTuning(String, int): double` — nudge by N scroll clicks; the mod
 *    quantises to its own step and (server-side) mirrors the value onto the
 *    CONNECTED track chain itself. We drive exact targets by computing the
 *    click delta from the current value, so our writes land on the same
 *    quantised lattice the in-game key would produce.
 *
 * All entry points are safe no-ops when the mod is absent.
 */
object TracksPlusTuning {

    const val MOD_ID = "tracks"

    /** One tuning knob: Tracks+ key name + UI label + slider range/step
     *  (mirrors the clamps in `SableTrackBlockEntity.adjustTuning`). */
    data class Spec(val key: String, val label: String, val min: Double, val max: Double, val step: Double)

    val SPECS: List<Spec> = listOf(
        Spec("strength", "Suspension Strength", 5.0, 180.0, 5.0),
        Spec("spring", "Spring", 0.1, 4.0, 0.05),
        Spec("damping", "Damping", 0.1, 4.0, 0.05),
        Spec("grip", "Grip", 0.1, 4.0, 0.05),
        Spec("drive", "Drive", 0.1, 4.0, 0.1),
        Spec("bump_clearance", "Bump Clearance", 0.1, 4.0, 0.05),
        Spec("bump_force", "Bump Force", 0.1, 4.0, 0.05),
        Spec("max_impulse", "Max Impulse", 0.1, 4.0, 0.05),
    )

    fun spec(key: String): Spec? = SPECS.firstOrNull { it.key == key }

    fun loaded(): Boolean = ModList.get().isLoaded(MOD_ID)

    private val beClass: Class<*>? by lazy {
        runCatching { Class.forName("dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity") }.getOrNull()
    }
    private val getTuning by lazy {
        runCatching { beClass?.getMethod("getTuning", String::class.java) }.getOrNull()
    }
    private val adjustTuning by lazy {
        runCatching { beClass?.getMethod("adjustTuning", String::class.java, Int::class.javaPrimitiveType) }.getOrNull()
    }

    fun isTrack(be: BlockEntity?): Boolean = be != null && beClass?.isInstance(be) == true

    /** Snapshot every known knob of [be] into a tag (missing/erroring knobs skipped). */
    fun read(be: BlockEntity): CompoundTag {
        val tag = CompoundTag()
        val m = getTuning ?: return tag
        for (s in SPECS) {
            runCatching { (m.invoke(be, s.key) as Number).toDouble() }.onSuccess { tag.putDouble(s.key, it) }
        }
        return tag
    }

    /** Drive [be]'s knob [key] to [target] via click deltas (quantised to the
     *  mod's own step). Returns true if a call was made. */
    fun apply(be: BlockEntity, key: String, target: Double): Boolean {
        val s = spec(key) ?: return false
        val g = getTuning ?: return false
        val a = adjustTuning ?: return false
        return runCatching {
            val cur = (g.invoke(be, key) as Number).toDouble()
            val clicks = ((target.coerceIn(s.min, s.max) - cur) / s.step).roundToInt()
            if (clicks != 0) a.invoke(be, key, clicks)
            true
        }.getOrDefault(false)
    }

}
