package dev.nitka.nodewire.integration.cbc

import com.mojang.logging.LogUtils
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

    /** Reflected yaw/pitch fields, cached per concrete BE class. */
    private class MountFields(val yaw: Field, val pitch: Field)

    private val mountFieldCache = HashMap<Class<*>, MountFields?>()

    @Synchronized
    private fun mountFieldsFor(cls: Class<*>): MountFields? = mountFieldCache.getOrPut(cls) {
        var c: Class<*>? = cls
        while (c != null && c.name != MOUNT_BE_FQN) c = c.superclass
        if (c == null) return@getOrPut null
        runCatching {
            val yaw = c.getDeclaredField("cannonYaw").apply { isAccessible = true }
            val pitch = c.getDeclaredField("cannonPitch").apply { isAccessible = true }
            MountFields(yaw, pitch)
        }.getOrNull()
    }

    private class MountPort(private val be: BlockEntity, private val fields: MountFields) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> = listOf(
            LinkPin(YAW_PIN, PinType.FLOAT, "cannon yaw"),
            LinkPin(PITCH_PIN, PinType.FLOAT, "cannon pitch"),
            LinkPin(POS_PIN, PinType.VEC3, "mount position"),
            LinkPin(POS_TEXT_PIN, PinType.STRING, "mount position (text)"),
        )

        override fun readPin(id: String): PinReading? = when (id) {
            YAW_PIN, PITCH_PIN -> {
                val field = if (id == YAW_PIN) fields.yaw else fields.pitch
                runCatching { field.getFloat(be) }.getOrNull()
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

    const val YAW_PIN = "cannon_yaw"
    const val PITCH_PIN = "cannon_pitch"
    const val POS_PIN = "position"
    const val POS_TEXT_PIN = "position_text"
}
