package dev.nitka.nodewire.integration.offroad

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinValueConversion
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinReading
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * Kotlin-side glue for `MixinWheelMountBlockEntity` (offroad's Wheel Mount).
 *
 * The mixin is a Java class that turns the foreign BE into a [PinLink]
 * sink; this object holds the parts that are awkward to touch from Java —
 * the sealed [PinValue] constructors, the [PinLink] codec list, and the
 * Sable-aware [EndpointRef] world centre — so the mixin only does field
 * storage + the `@Inject` plumbing. All entry points are `@JvmStatic` so the
 * mixin calls them without the `INSTANCE` indirection.
 *
 * Pin ids mirror the cannon-mount port ([dev.nitka.nodewire.integration.cbc.CbcIntegration])
 * for `position`/`position_text`, so any fire-control wiring reads the same
 * id off either mount kind.
 */
object WheelMountPinGlue {
    /** Live wheel angle (degrees), read from the shadowed `angle` field. */
    const val STEERING_ANGLE_PIN = "steering_angle"

    /** Sable-aware mount centre — follows the vehicle sub-level. */
    const val POSITION_PIN = "position"
    const val POSITION_TEXT_PIN = "position_text"

    /** Drives `getSteeringSignal` while a link feeds it (server-side). */
    const val TARGET_STEERING_PIN = "target_steering"

    private const val TAG_PIN_LINKS = "pin_links"

    /** Max steering magnitude — the redstone-signal range the wheel reads off a
     *  fully-powered side face (a lever = 15). A normalized command of ±1 maps
     *  onto ±this for a full lock. */
    private const val MAX_STEER = 15

    @JvmStatic
    fun pinOutputs(): List<LinkPin> = listOf(
        LinkPin(STEERING_ANGLE_PIN, PinType.FLOAT, "steering angle"),
        LinkPin(POSITION_PIN, PinType.VEC3, "mount position"),
        LinkPin(POSITION_TEXT_PIN, PinType.STRING, "mount position (text)"),
    )

    @JvmStatic
    fun pinInputs(): List<LinkPin> = listOf(
        LinkPin(TARGET_STEERING_PIN, PinType.FLOAT, "target steering"),
    )

    /** `steering_angle` reading from the per-tick wheel angle. */
    @JvmStatic
    fun steeringAngleReading(angle: Double): PinReading =
        PinReading(PinValue.Float(angle.toFloat()))

    /** `position` (VEC3) reading; null when the centre can't be resolved. */
    @JvmStatic
    fun positionReading(be: BlockEntity): PinReading? =
        worldCenter(be)?.let { PinReading(PinValue.Vec3(it.x, it.y, it.z)) }

    /** `position_text` (STRING) reading for string-based fire-control wiring. */
    @JvmStatic
    fun positionTextReading(be: BlockEntity): PinReading? =
        worldCenter(be)?.let { PinReading(PinValue.Str("${it.x} ${it.y} ${it.z}")) }

    /**
     * Coerce a delivered `target_steering` value into the int
     * `getSteeringSignal` returns. Null = nothing usable arrived (leave the
     * override unchanged).
     *
     * `target_steering` is a FLOAT pin, so whatever arrives is first converted to
     * FLOAT (Int `1` → `1.0`, Bool `true` → `1.0`, …) and treated as ONE
     * normalized command: `-1` = full left, `+1` = full right, mapped onto
     * ±[MAX_STEER] (so a normalized -1 turns as hard as a redstone lever would).
     */
    @JvmStatic
    fun targetSteering(value: PinValue): Int? {
        val f = (PinValueConversion.convert(value, PinType.FLOAT) as? PinValue.Float)?.value ?: return null
        return Math.round(f.coerceIn(-1f, 1f) * MAX_STEER)
    }

    /** Sable-aware mount centre; block centre fallback when no backend resolves. */
    private fun worldCenter(be: BlockEntity): Vec3? {
        val lvl = be.level ?: return null
        return runCatching {
            EndpointRef.from(lvl, be.blockPos).worldCenter(lvl)
        }.getOrNull() ?: Vec3.atCenterOf(be.blockPos)
    }

    /** Persist [links] under `pin_links` (mirrors the per-BE sinks). No-op when empty. */
    @JvmStatic
    fun writePinLinks(tag: CompoundTag, links: List<PinLink>) {
        if (links.isEmpty()) return
        PinLink.CODEC.listOf()
            .encodeStart(NbtOps.INSTANCE, links)
            .result().ifPresent { tag.put(TAG_PIN_LINKS, it) }
    }

    /** Load persisted links into [out] (cleared first). */
    @JvmStatic
    fun readPinLinks(tag: CompoundTag, out: MutableList<PinLink>) {
        out.clear()
        if (!tag.contains(TAG_PIN_LINKS)) return
        PinLink.CODEC.listOf()
            .parse(NbtOps.INSTANCE, tag.get(TAG_PIN_LINKS))
            .result().ifPresent { out.addAll(it) }
    }
}
