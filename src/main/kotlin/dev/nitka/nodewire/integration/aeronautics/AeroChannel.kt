package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * One (kind, channel, displayName, pinType, read) row per supported data
 * point. [read] uses reflection to pull the value from the BE because the
 * Aeronautics jar is JarJar-wrapped and not on the compile classpath.
 *
 * Reflection failures (renamed field, missing method, unexpected null)
 * surface as exceptions caught by [AeroStatePipeline.snapshot] which then
 * emits zero. We never crash the eval pipeline on a single bad read.
 */
enum class AeroChannel(
    val kind: AeroBlockKind,
    val displayName: String,
    val pinType: PinType,
    /**
     * `true` means the BE exposes an effective external write path (a
     * setter that isn't immediately overwritten by the next tick). For
     * Aeronautics 1.2.1 only redstone-strength-style fields qualify —
     * the rest are kinetic-network driven or computed and any write
     * would be undone on the next tick.
     *
     * Surfaced in the channel picker UI as a 🔓 / 🔒 badge so the user
     * knows up-front which bindings could power a hypothetical
     * Aeronautics Output node.
     */
    val writable: Boolean = false,
) {
    // --- Propeller family (Andesite / Wooden / Smart all extend
    // BasePropellerBlockEntity which exposes: public field rotationSpeed,
    // public methods getThrust(): double, getAirflow(): double,
    // isActive(): boolean, getDirectionIndependentSpeed(): float.) ---

    PROP_ROTATION_SPEED(AeroBlockKind.SMART_PROPELLER, "Rotation speed", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "rotationSpeed"))
    },
    PROP_THRUST(AeroBlockKind.SMART_PROPELLER, "Thrust", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getThrust").toFloat())
    },
    PROP_AIRFLOW(AeroBlockKind.SMART_PROPELLER, "Airflow", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getAirflow").toFloat())
    },
    PROP_ACTIVE(AeroBlockKind.SMART_PROPELLER, "Active", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "isActive"))
    },
    PROP_SPEED_ABS(AeroBlockKind.SMART_PROPELLER, "Speed (abs)", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeFloat(be, "getDirectionIndependentSpeed"))
    },
    // Smart-only fields: thrustDir (public Vector3d), getLerpedHingeAngle(partialTicks): float
    SMART_PROP_THRUST_DIR(AeroBlockKind.SMART_PROPELLER, "Thrust direction", PinType.VEC3) {
        override fun read(be: BlockEntity): PinValue = R.vec3FromVector3dField(be, "thrustDir")
    },
    SMART_PROP_HINGE_ANGLE(AeroBlockKind.SMART_PROPELLER, "Hinge angle", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue =
            PinValue.Float(R.invokeFloat1f(be, "getLerpedHingeAngle", 1f))
    },

    // Mirror across kinds — Andesite/Wooden share BasePropellerBlockEntity reads
    ANDESITE_PROP_ROTATION_SPEED(AeroBlockKind.ANDESITE_PROPELLER, "Rotation speed", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "rotationSpeed"))
    },
    ANDESITE_PROP_THRUST(AeroBlockKind.ANDESITE_PROPELLER, "Thrust", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getThrust").toFloat())
    },
    ANDESITE_PROP_AIRFLOW(AeroBlockKind.ANDESITE_PROPELLER, "Airflow", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getAirflow").toFloat())
    },
    ANDESITE_PROP_ACTIVE(AeroBlockKind.ANDESITE_PROPELLER, "Active", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "isActive"))
    },
    ANDESITE_PROP_SPEED_ABS(AeroBlockKind.ANDESITE_PROPELLER, "Speed (abs)", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeFloat(be, "getDirectionIndependentSpeed"))
    },
    WOODEN_PROP_ROTATION_SPEED(AeroBlockKind.WOODEN_PROPELLER, "Rotation speed", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "rotationSpeed"))
    },
    WOODEN_PROP_THRUST(AeroBlockKind.WOODEN_PROPELLER, "Thrust", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getThrust").toFloat())
    },
    WOODEN_PROP_AIRFLOW(AeroBlockKind.WOODEN_PROPELLER, "Airflow", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getAirflow").toFloat())
    },
    WOODEN_PROP_ACTIVE(AeroBlockKind.WOODEN_PROPELLER, "Active", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "isActive"))
    },
    WOODEN_PROP_SPEED_ABS(AeroBlockKind.WOODEN_PROPELLER, "Speed (abs)", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeFloat(be, "getDirectionIndependentSpeed"))
    },

    // --- Hot Air Burner: protected `powered: boolean`, protected `signalStrength: int`,
    //     public getGasOutput(): double, canOutputGas(): boolean, getClientPredictedVolume(): double ---
    BURNER_POWERED(AeroBlockKind.HOT_AIR_BURNER, "Powered", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.bool(be, "powered"))
    },
    BURNER_SIGNAL(AeroBlockKind.HOT_AIR_BURNER, "Signal", PinType.REDSTONE, writable = true) {
        override fun read(be: BlockEntity): PinValue = PinValue.Redstone(R.int(be, "signalStrength"))
    },
    BURNER_GAS_OUTPUT(AeroBlockKind.HOT_AIR_BURNER, "Gas output", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getGasOutput").toFloat())
    },
    BURNER_CAN_OUTPUT_GAS(AeroBlockKind.HOT_AIR_BURNER, "Can output gas", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "canOutputGas"))
    },
    BURNER_BALLOON_VOLUME(AeroBlockKind.HOT_AIR_BURNER, "Balloon volume", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getClientPredictedVolume").toFloat())
    },

    // --- Steam Vent: public `signalStrength: int`, public `rawSignalStrength: int`,
    //     public getGasOutput, canOutputGas, getClientPredictedVolume ---
    VENT_SIGNAL(AeroBlockKind.STEAM_VENT, "Signal", PinType.REDSTONE, writable = true) {
        override fun read(be: BlockEntity): PinValue = PinValue.Redstone(R.int(be, "signalStrength"))
    },
    VENT_RAW_SIGNAL(AeroBlockKind.STEAM_VENT, "Raw signal", PinType.REDSTONE) {
        override fun read(be: BlockEntity): PinValue = PinValue.Redstone(R.int(be, "rawSignalStrength"))
    },
    VENT_GAS_OUTPUT(AeroBlockKind.STEAM_VENT, "Gas output", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getGasOutput").toFloat())
    },
    VENT_CAN_OUTPUT_GAS(AeroBlockKind.STEAM_VENT, "Can output gas", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "canOutputGas"))
    },
    VENT_BALLOON_VOLUME(AeroBlockKind.STEAM_VENT, "Balloon volume", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getClientPredictedVolume").toFloat())
    },

    // --- Mounted Potato Cannon: private chargeTimer:float, recoilMagnitude:float;
    //     public isBlocked():boolean, getBlockedLength():double,
    //     public getAimingVector():Vec3, getBarrelPos():Vec3 ---
    CANNON_CHARGE_PROGRESS(AeroBlockKind.MOUNTED_POTATO_CANNON, "Charge progress", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "chargeTimer"))
    },
    CANNON_RECOIL(AeroBlockKind.MOUNTED_POTATO_CANNON, "Recoil", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "recoilMagnitude"))
    },
    CANNON_BLOCKED(AeroBlockKind.MOUNTED_POTATO_CANNON, "Blocked", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "isBlocked"))
    },
    CANNON_BLOCKED_LENGTH(AeroBlockKind.MOUNTED_POTATO_CANNON, "Blocked length", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getBlockedLength").toFloat())
    },
    CANNON_AIM(AeroBlockKind.MOUNTED_POTATO_CANNON, "Aim direction", PinType.VEC3) {
        override fun read(be: BlockEntity): PinValue = R.vec3FromMcVec3Method(be, "getAimingVector")
    },
    CANNON_BARREL_POS(AeroBlockKind.MOUNTED_POTATO_CANNON, "Barrel position", PinType.VEC3) {
        override fun read(be: BlockEntity): PinValue = R.vec3FromMcVec3Method(be, "getBarrelPos")
    },

    // --- Propeller Bearing: public Vector3d thrustDirection, facingDirection;
    //     public float totalSailPower; getThrust, getAirflow, isActive,
    //     getDirectionIndependentSpeed, getAngularSpeed ---
    BEARING_THRUST(AeroBlockKind.PROPELLER_BEARING, "Thrust", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getThrust").toFloat())
    },
    BEARING_AIRFLOW(AeroBlockKind.PROPELLER_BEARING, "Airflow", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeDouble(be, "getAirflow").toFloat())
    },
    BEARING_ACTIVE(AeroBlockKind.PROPELLER_BEARING, "Active", PinType.BOOL) {
        override fun read(be: BlockEntity): PinValue = PinValue.Bool(R.invokeBool(be, "isActive"))
    },
    BEARING_ANGULAR_SPEED(AeroBlockKind.PROPELLER_BEARING, "Angular speed", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeFloat(be, "getAngularSpeed"))
    },
    BEARING_SPEED_ABS(AeroBlockKind.PROPELLER_BEARING, "Speed (abs)", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.invokeFloat(be, "getDirectionIndependentSpeed"))
    },
    BEARING_THRUST_DIR(AeroBlockKind.PROPELLER_BEARING, "Thrust direction", PinType.VEC3) {
        override fun read(be: BlockEntity): PinValue = R.vec3FromVector3dField(be, "thrustDirection")
    },
    BEARING_FACING_DIR(AeroBlockKind.PROPELLER_BEARING, "Facing direction", PinType.VEC3) {
        override fun read(be: BlockEntity): PinValue = R.vec3FromVector3dField(be, "facingDirection")
    },
    BEARING_SAIL_POWER(AeroBlockKind.PROPELLER_BEARING, "Sail power", PinType.FLOAT) {
        override fun read(be: BlockEntity): PinValue = PinValue.Float(R.float(be, "totalSailPower"))
    };

    abstract fun read(be: BlockEntity): PinValue

    companion object {
        fun byKind(kind: AeroBlockKind): List<AeroChannel> = entries.filter { it.kind == kind }
        fun fromName(name: String): AeroChannel? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Reflection helper. Walks the class hierarchy to find fields/methods —
 * needed because Aero BEs are subclasses of Create's KineticBlockEntity
 * (Smart -> Base -> KineticBE) and the actual fields/methods may live on
 * any ancestor.
 */
private object R {
    fun findField(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val f = c.getDeclaredField(name); f.isAccessible = true; return f
            } catch (_: NoSuchFieldException) { /* keep walking */ }
            c = c.superclass
        }
        throw NoSuchFieldException("$name on ${cls.name}")
    }

    fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *paramTypes); m.isAccessible = true; return m
            } catch (_: NoSuchMethodException) { /* keep walking */ }
            c = c.superclass
        }
        throw NoSuchMethodException("$name on ${cls.name}")
    }

    fun float(be: BlockEntity, name: String): kotlin.Float =
        findField(be.javaClass, name).getFloat(be)
    fun int(be: BlockEntity, name: String): kotlin.Int =
        findField(be.javaClass, name).getInt(be)
    fun bool(be: BlockEntity, name: String): kotlin.Boolean =
        findField(be.javaClass, name).getBoolean(be)

    fun invokeDouble(be: BlockEntity, name: String): kotlin.Double =
        findMethod(be.javaClass, name).invoke(be) as kotlin.Double
    fun invokeFloat(be: BlockEntity, name: String): kotlin.Float =
        findMethod(be.javaClass, name).invoke(be) as kotlin.Float
    fun invokeBool(be: BlockEntity, name: String): kotlin.Boolean =
        findMethod(be.javaClass, name).invoke(be) as kotlin.Boolean
    fun invokeFloat1f(be: BlockEntity, name: String, arg: kotlin.Float): kotlin.Float =
        findMethod(be.javaClass, name, kotlin.Float::class.javaPrimitiveType!!).invoke(be, arg) as kotlin.Float

    /** Read a public Vector3d field and pack into PinValue.Vec3. */
    fun vec3FromVector3dField(be: BlockEntity, name: String): PinValue {
        val v = findField(be.javaClass, name).get(be) as Vector3d
        return PinValue.Vec3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    }

    /** Invoke a no-arg method returning Minecraft's Vec3 and pack into PinValue.Vec3. */
    fun vec3FromMcVec3Method(be: BlockEntity, name: String): PinValue {
        val v = findMethod(be.javaClass, name).invoke(be) as Vec3
        return PinValue.Vec3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    }
}
