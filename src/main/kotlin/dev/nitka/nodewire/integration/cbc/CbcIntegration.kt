package dev.nitka.nodewire.integration.cbc

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.config.NodewireConfig
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPort
import dev.nitka.nodewire.link.PinReading
import dev.nitka.nodewire.script.Cbc
import dev.nitka.nodewire.script.PropellantBallistics
import dev.nitka.nodewire.script.ShellBallistics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.fml.ModList
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Create Big Cannons integration.
 *
 * 1. **Ballistics provider** — installs [Cbc.provider] so scripts get live
 *    shell/propellant profiles. Everything is read from CBC's RUNTIME
 *    munition-properties registry (`MunitionPropertiesHandler`'s maps, the
 *    same data-driven JSONs `/reload` refreshes), so projectiles registered
 *    by CBC addons or datapacks are picked up with zero code here. Access is
 *    by qualified-name reflection — CBC is not a compile dependency.
 *
 * 2. **Cannon-mount pins** — a [PinPort] adapter exposing the live
 *    `yaw`/`pitch` of a `CannonMountBlockEntity`, so a fire-control script
 *    can read the TRUE barrel orientation instead of dead-reckoning it.
 *
 * Properties records follow CBC's component convention: each properties
 * record exposes a no-arg `ballistics()` accessor (gravity/drag/quadratic)
 * and big-cannon shells add `bigCannonProperties()` (charge power); the
 * propellant records expose `propellantProperties()` (strength). Addons use
 * the same records, so the reflection works for them too.
 */
object CbcIntegration {
    private val LOG = LogUtils.getLogger()
    private const val MOD_ID = "createbigcannons"
    private const val HANDLER_FQN = "rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler"
    private const val TYPE_HANDLER_FQN = "rbasamoyai.createbigcannons.munitions.config.PropertiesTypeHandler"
    private const val MOUNT_BE_FQN = "rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity"

    /** CBC: Compact Mount addon — a pitch-only mount that does NOT extend
     *  [MOUNT_BE_FQN] (it extends KineticBlockEntity, implements ExtendsCannonMount)
     *  but carries the same `cannonPitch` field. No `cannonYaw` — it can't rotate. */
    private const val COMPACT_MOUNT_BE_FQN = "com.cubester.cbc_compact_mount.content.CompactCannonMountBlockEntity"

    /** Every mount-like BE we expose as pins, walked up the class hierarchy. */
    private val MOUNT_FQNS = listOf(MOUNT_BE_FQN, COMPACT_MOUNT_BE_FQN)

    /** Wire the provider once at mod init. No-op when CBC is absent. */
    fun init() {
        if (!ModList.get().isLoaded(MOD_ID)) return
        Cbc.provider = ReflectiveProvider
        LOG.info("[nodewire/cbc] CBC ballistics provider installed")
    }

    // ── ballistics provider ──────────────────────────────────────────────

    private object ReflectiveProvider : Cbc.Provider {
        /** Snapshot cache: properties only change on /reload, but scripts ask
         *  every tick — rebuild at most every [TTL_NANOS]. */
        private const val TTL_NANOS = 10_000_000_000L

        @Volatile private var cachedShells: List<ShellBallistics> = emptyList()
        @Volatile private var cachedPropellants: List<PropellantBallistics> = emptyList()
        @Volatile private var builtAt = 0L

        override fun shells(): List<ShellBallistics> {
            refresh()
            return cachedShells
        }

        override fun propellants(): List<PropellantBallistics> {
            refresh()
            return cachedPropellants
        }

        private fun refresh() {
            val now = System.nanoTime()
            if (builtAt != 0L && now - builtAt < TTL_NANOS) return
            synchronized(this) {
                if (builtAt != 0L && System.nanoTime() - builtAt < TTL_NANOS) return
                runCatching { rebuild() }
                    .onFailure { LOG.warn("[nodewire/cbc] properties read failed", it) }
                builtAt = System.nanoTime()
            }
        }

        private fun rebuild() {
            val handlerClass = Class.forName(HANDLER_FQN)
            val getProps = Class.forName(TYPE_HANDLER_FQN)
                .getMethod("getPropertiesOf", Any::class.java)

            fun handlerMap(fieldName: String): Map<*, *> {
                val f: Field = handlerClass.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(null) as? Map<*, *> ?: emptyMap<Any, Any>()
            }

            val shells = ArrayList<ShellBallistics>()
            for ((type, handler) in handlerMap("PROJECTILES")) {
                if (type !is EntityType<*> || handler == null) continue
                val id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()
                val props = runCatching { getProps.invoke(handler, type) }.getOrNull() ?: continue
                val ballistics = componentOf(props, "ballistics") ?: continue
                // The charge-power component is found STRUCTURALLY (any no-arg
                // accessor whose result has addedChargePower()) — CBC's
                // bigCannonProperties(), Military Supplement's
                // dualCannonProperties() and future addon shapes all match.
                val charge = chargeComponentOf(props)
                shells.add(
                    ShellBallistics(
                        id = id,
                        gravity = readDouble(ballistics, "gravity") ?: continue,
                        drag = readDouble(ballistics, "drag") ?: continue,
                        quadraticDrag = readBoolean(ballistics, "isQuadraticDrag") ?: false,
                        addedChargePower = charge?.let { readFloat(it, "addedChargePower") } ?: 0f,
                        minimumChargePower = charge?.let { readFloat(it, "minimumChargePower") } ?: 0f,
                        initialVelocity = charge?.let { readFloat(it, "initialVel") } ?: 0f,
                    ),
                )
            }

            val propellants = ArrayList<PropellantBallistics>()
            fun collectPropellants(fieldName: String, idOf: (Any) -> String?) {
                for ((key, handler) in handlerMap(fieldName)) {
                    if (key == null || handler == null) continue
                    val id = idOf(key) ?: continue
                    val props = runCatching { getProps.invoke(handler, key) }.getOrNull() ?: continue
                    val component = componentOf(props, "propellantProperties") ?: continue
                    val strength = readFloat(component, "strength") ?: continue
                    propellants.add(PropellantBallistics(id, strength))
                }
            }
            collectPropellants("BLOCK_PROPELLANT") { k ->
                (k as? Block)?.let { BuiltInRegistries.BLOCK.getKey(it).toString() }
            }
            collectPropellants("ITEM_PROPELLANT") { k ->
                (k as? Item)?.let { BuiltInRegistries.ITEM.getKey(it).toString() }
            }

            cachedShells = shells.sortedBy { it.id }
            cachedPropellants = propellants.distinctBy { it.id }.sortedBy { it.id }
        }

        private fun componentOf(record: Any, accessor: String): Any? =
            runCatching { record.javaClass.getMethod(accessor).invoke(record) }.getOrNull()

        /** First component of [record] that carries `addedChargePower()`. */
        private fun chargeComponentOf(record: Any): Any? =
            record.javaClass.methods
                .filter {
                    it.parameterCount == 0 &&
                        it.declaringClass != Any::class.java &&
                        !it.returnType.isPrimitive &&
                        it.returnType != String::class.java
                }
                .firstNotNullOfOrNull { m ->
                    runCatching {
                        val comp = m.invoke(record) ?: return@runCatching null
                        comp.takeIf { c ->
                            c.javaClass.methods.any { it.name == "addedChargePower" && it.parameterCount == 0 }
                        }
                    }.getOrNull()
                }

        private fun readDouble(record: Any, accessor: String): Double? =
            (componentOf(record, accessor) as? Number)?.toDouble()

        private fun readFloat(record: Any, accessor: String): Float? =
            (componentOf(record, accessor) as? Number)?.toFloat()

        private fun readBoolean(record: Any, accessor: String): Boolean? =
            componentOf(record, accessor) as? Boolean

        /** Test seam: drop the snapshot so the next call rebuilds. */
        fun invalidate() {
            builtAt = 0L
        }
    }

    // ── cannon-mount pins ────────────────────────────────────────────────

    /** Adapter port for a CBC cannon mount, or null when [be] isn't one
     *  (or CBC is absent). Called from PinPorts composition. */
    fun mountPortFor(be: BlockEntity): PinPort? {
        if (!ModList.get().isLoaded(MOD_ID)) return null
        val fields = mountFieldsFor(be.javaClass) ?: return null
        return MountPort(be, fields)
    }

    /** Reflected mount accessors, cached per concrete BE class. [yaw] is null for
     *  pitch-only mounts (the Compact Mount addon has no `cannonYaw`); [contraption]
     *  backs the `mounted` pin; [setPitch]/[setYaw] back the pin-driven-aim cheat. */
    private class MountFields(
        val yaw: Field?,
        val pitch: Field,
        val contraption: Method?,
        val setPitch: Method?,
        val setYaw: Method?,
    )

    private val mountFieldCache = HashMap<Class<*>, MountFields?>()

    @Synchronized
    private fun mountFieldsFor(cls: Class<*>): MountFields? = mountFieldCache.getOrPut(cls) {
        var c: Class<*>? = cls
        while (c != null && c.name !in MOUNT_FQNS) c = c.superclass
        if (c == null) return@getOrPut null
        runCatching {
            // `cannonPitch` is required; `cannonYaw` is optional — the Compact
            // Mount can't rotate, so it omits the field.
            val pitch = c.getDeclaredField("cannonPitch").apply { isAccessible = true }
            val yaw = runCatching { c.getDeclaredField("cannonYaw").apply { isAccessible = true } }.getOrNull()
            // No-arg `getContraption()` → the assembled cannon (or null). Optional
            // so a mount lacking it still links its angle pins.
            val contraption = runCatching { c.getDeclaredMethod("getContraption").apply { isAccessible = true } }.getOrNull()
            // Public aim setters (pin-driven-aim cheat). getMethod walks the
            // hierarchy + interfaces — compact mounts inherit setPitch from
            // ControlPitchContraption but have no setYaw (no yaw axis).
            val fl = Float::class.javaPrimitiveType
            val setPitch = runCatching { c.getMethod("setPitch", fl) }.getOrNull()
            val setYaw = runCatching { c.getMethod("setYaw", fl) }.getOrNull()
            MountFields(yaw, pitch, contraption, setPitch, setYaw)
        }.getOrNull()
    }

    private class MountPort(private val be: BlockEntity, private val fields: MountFields) : PinPort {
        /** Pitch-only mounts (Compact Mount) drop every yaw-derived pin. */
        private val hasYaw: Boolean get() = fields.yaw != null

        override fun pinOutputs(ctx: LinkContext): List<LinkPin> = buildList {
            if (hasYaw) add(LinkPin(YAW_PIN, PinType.FLOAT, "cannon yaw"))
            add(LinkPin(PITCH_PIN, PinType.FLOAT, "cannon pitch"))
            add(LinkPin(POS_PIN, PinType.VEC3, "mount position"))
            add(LinkPin(POS_TEXT_PIN, PinType.STRING, "mount position (text)"))
            // Is a cannon currently assembled on the mount?
            if (fields.contraption != null) add(LinkPin(MOUNTED_PIN, PinType.BOOL, "mounted"))
            // Motion (derived from the per-tick angle delta — CBC exposes no
            // target/speed directly): is the barrel still slewing?
            add(LinkPin(PITCHING_PIN, PinType.BOOL, "pitching"))
            if (hasYaw) add(LinkPin(YAWING_PIN, PinType.BOOL, "yawing"))
            add(LinkPin(AIMING_PIN, PinType.BOOL, "aiming"))
            add(LinkPin(PITCH_SPEED_PIN, PinType.FLOAT, "pitch speed °/t"))
            if (hasYaw) add(LinkPin(YAW_SPEED_PIN, PinType.FLOAT, "yaw speed °/t"))
        }

        // ── pin-driven aim (cheat) ────────────────────────────────────────
        // Input pins exist ONLY when the server config enables the cheat; with
        // it off the mount is read-only and there's nothing to link into.

        override fun pinInputs(ctx: LinkContext): List<LinkPin> {
            if (!NodewireConfig.pinDrivenCannonAim.get()) return emptyList()
            return buildList {
                if (fields.setPitch != null) add(LinkPin(TARGET_PITCH_PIN, PinType.FLOAT, "target pitch"))
                if (fields.setYaw != null) add(LinkPin(TARGET_YAW_PIN, PinType.FLOAT, "target yaw"))
            }
        }

        override fun writePin(id: String, value: PinValue) {
            if (!NodewireConfig.pinDrivenCannonAim.get()) return
            val angle = (value as? PinValue.Float)?.value ?: return
            val setter = when (id) {
                TARGET_PITCH_PIN -> fields.setPitch
                TARGET_YAW_PIN -> fields.setYaw
                else -> null
            } ?: return
            runCatching { setter.invoke(be, angle) }
        }

        override fun readPin(id: String): PinReading? = when (id) {
            YAW_PIN, PITCH_PIN -> {
                val field = if (id == YAW_PIN) fields.yaw else fields.pitch
                field?.let { f -> runCatching { f.getFloat(be) }.getOrNull() }
                    ?.let { PinReading(PinValue.Float(it)) }
            }
            // VEC3 pins carry doubles since the JOML migration, so the
            // vector pin is lossless even at far-lands coordinates.
            POS_PIN -> worldCenter()?.let {
                PinReading(PinValue.Vec3(it.x, it.y, it.z))
            }
            // Text variant kept for string-based fire-control wiring.
            POS_TEXT_PIN -> worldCenter()?.let {
                PinReading(PinValue.Str("${it.x} ${it.y} ${it.z}"))
            }
            // `mounted` — true once a cannon is assembled on the mount. A failed
            // reflective call yields no reading (target keeps its last value).
            MOUNTED_PIN -> {
                val m = fields.contraption ?: return null
                runCatching { m.invoke(be) }.fold(
                    onSuccess = { PinReading(PinValue.Bool(it != null)) },
                    onFailure = { null },
                )
            }
            PITCHING_PIN, YAWING_PIN, AIMING_PIN, PITCH_SPEED_PIN, YAW_SPEED_PIN -> {
                val m = motionFor(be, fields) ?: return null
                when (id) {
                    PITCH_SPEED_PIN -> PinReading(PinValue.Float(m.dPitch))
                    YAW_SPEED_PIN -> PinReading(PinValue.Float(m.dYaw))
                    PITCHING_PIN -> PinReading(PinValue.Bool(kotlin.math.abs(m.dPitch) > MOVE_EPS))
                    YAWING_PIN -> PinReading(PinValue.Bool(kotlin.math.abs(m.dYaw) > MOVE_EPS))
                    else -> PinReading(
                        PinValue.Bool(kotlin.math.abs(m.dPitch) > MOVE_EPS || kotlin.math.abs(m.dYaw) > MOVE_EPS),
                    )
                }
            }
            else -> null
        }

        /** World-space mount centre (Sable-aware: follows the structure). */
        private fun worldCenter(): net.minecraft.world.phys.Vec3? {
            val lvl = be.level ?: return null
            return runCatching {
                dev.nitka.nodewire.endpoint.EndpointRef.from(lvl, be.blockPos).worldCenter(lvl)
            }.getOrNull() ?: net.minecraft.world.phys.Vec3.atCenterOf(be.blockPos)
        }
    }

    /** Per-mount angular motion derived from the per-tick angle delta (CBC
     *  exposes no target/speed). */
    private class MountMotion {
        private var lastTick = Long.MIN_VALUE
        private var lastYaw = Float.NaN
        private var lastPitch = Float.NaN
        var dYaw = 0f
            private set
        var dPitch = 0f
            private set

        /** Idempotent within a game tick: computes the slew delta once per tick. */
        fun tick(gameTime: Long, yaw: Float, pitch: Float) {
            if (gameTime == lastTick) return
            if (!lastYaw.isNaN()) {
                dYaw = shortestAngleDelta(lastYaw, yaw)
                dPitch = shortestAngleDelta(lastPitch, pitch)
            }
            lastYaw = yaw
            lastPitch = pitch
            lastTick = gameTime
        }
    }

    private val mountMotion = java.util.WeakHashMap<BlockEntity, MountMotion>()

    /** Read the live yaw/pitch and advance the per-tick motion tracker. Server
     *  thread (PinLinkEngine); null if the reflected fields can't be read. */
    @Synchronized
    private fun motionFor(be: BlockEntity, fields: MountFields): MountMotion? {
        // yaw is absent on pitch-only mounts → treat it as constant 0, so the
        // yaw-delta stays 0 (no slew on an axis that can't move).
        val yaw = fields.yaw?.let { runCatching { it.getFloat(be) }.getOrNull() ?: return null } ?: 0f
        val pitch = runCatching { fields.pitch.getFloat(be) }.getOrNull() ?: return null
        val m = mountMotion.getOrPut(be) { MountMotion() }
        m.tick(be.level?.gameTime ?: 0L, yaw, pitch)
        return m
    }

    const val YAW_PIN = "cannon_yaw"
    const val PITCH_PIN = "cannon_pitch"
    const val POS_PIN = "position"
    const val POS_TEXT_PIN = "position_text"
    const val MOUNTED_PIN = "mounted"
    const val TARGET_PITCH_PIN = "target_pitch"
    const val TARGET_YAW_PIN = "target_yaw"
    const val PITCHING_PIN = "pitching"
    const val YAWING_PIN = "yawing"
    const val AIMING_PIN = "aiming"
    const val PITCH_SPEED_PIN = "pitch_speed"
    const val YAW_SPEED_PIN = "yaw_speed"

    /** Below this (°/tick) an axis counts as stopped. */
    private const val MOVE_EPS = 0.01f
}

/** Shortest signed angular delta from→to, in (-180, 180] degrees. */
private fun shortestAngleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}
