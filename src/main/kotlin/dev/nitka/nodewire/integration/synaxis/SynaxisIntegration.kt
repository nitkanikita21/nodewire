package dev.nitka.nodewire.integration.synaxis

import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalType
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue
import com.verr1.synaxis.foundation.cimulink.game.body.GameThreadPlantPort
import com.verr1.synaxis.foundation.cimulink.game.body.PhysicsSafePlantPort
import com.verr1.synaxis.foundation.cimulink.game.body.PlantPort
import com.verr1.synaxis.foundation.cimulink.game.body.PlantPortProviders
import com.verr1.synaxis.foundation.cimulink.game.body.PlantRecord
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkWorldRuntimes
import net.minecraft.server.level.ServerLevel
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPort
import dev.nitka.nodewire.link.PinReading
import net.minecraft.world.level.block.entity.BlockEntity
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Synaxis (Cimulink) → unified pin linking. Any BlockEntity Synaxis can wrap
 * into a [PlantPort] (its motors/actuators/propellers/jets/flaps, the Control
 * Chair and Tweakerminal, plus its Create/Simulated compat adapters) exposes
 * that port's [com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema]
 * through the Channel Link Tool: schema outputs become readable pins, schema
 * inputs writable ones.
 *
 * IMPORTANT: this class references Synaxis types directly (compileOnly dep, no
 * jarjar wrapper in their jar) — it must only be LOADED behind a
 * `ModList.isLoaded("synaxis")` gate ([dev.nitka.nodewire.link.PinPorts] does).
 *
 * Pin ids carry a `syn:` prefix so a Synaxis port named e.g. `redstone` can
 * never collide with the lowercase redstone-fallback pins in a composite port.
 */
object SynaxisIntegration {

    /** Wrap [be] if Synaxis claims it; null when it isn't a Synaxis device. */
    fun portFor(be: BlockEntity): PinPort? {
        // Synaxis's OWN devices (Kinetic Resistor, motors, Control Chair…) are
        // REGISTERED Cimulink endpoints, not provider-wrapped — tryCreate never
        // matches them. Resolve those through the level runtime first.
        registeredPort(be)?.let { return it }
        val plant = runCatching {
            PlantPortProviders.tryCreate(be, syntheticId(be), DEVICE_NAME).orElse(null)
        }.getOrNull() ?: return null
        return SynaxisPort(be, plant)
    }

    /**
     * Registered-endpoint path, resolved by POSITION via the plant directory.
     * Synaxis devices self-register their port each server tick
     * (`refreshPlantRegistration` → `CimulinkLevelRuntime.registerPlantPort`)
     * WITHOUT implementing any marker interface, and their `plantEndpointId`
     * is `EndpointId.random()` per BE instance and never synced — so the
     * directory record (id + schema snapshot), keyed by pos, is the only
     * identity both sides can agree on. Checked for EVERY BE: liveRecords is
     * just the handful of registered devices, the scan is cheap.
     *
     * Client side hops to the integrated server via [ServerLifecycleHooks]
     * (same singleplayer-only limitation as host-less link surfacing);
     * enumeration only reads the record snapshot, reads/writes happen on the
     * server through [dev.nitka.nodewire.link.PinLinkEngine].
     */
    private fun registeredPort(be: BlockEntity): PinPort? {
        val lvl = be.level ?: return null
        val serverLevel = lvl as? ServerLevel
            ?: net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()?.getLevel(lvl.dimension())
            ?: return null
        val record = runCatching {
            CimulinkWorldRuntimes.forLevel(serverLevel).runtime().plantDirectory().liveRecords()
                .firstOrNull { it.loaded() && it.address().pos() == be.blockPos }
        }.getOrNull() ?: return null
        return RuntimePort(serverLevel, record)
    }

    /** Stable per-position endpoint id — Synaxis only uses it as identity. */
    private fun syntheticId(be: BlockEntity): EndpointId {
        val raw = "nodewire:${be.level?.dimension()?.location()}:${be.blockPos.asLong()}"
        return EndpointId.of(UUID.nameUUIDFromBytes(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    private const val DEVICE_NAME = "nodewire"
    private const val PREFIX = "syn:"

    private fun pinTypeOf(type: SignalType): PinType? = when (type) {
        SignalType.REAL -> PinType.FLOAT
        SignalType.BOOLEAN -> PinType.BOOL
        SignalType.VEC3 -> PinType.VEC3
        SignalType.QUATERNION -> PinType.QUAT
        else -> null // POSE / TWIST / BUNDLE — no PinValue shape for these yet
    }

    private fun toPinValue(v: SignalValue): PinValue? = when (v) {
        is SignalValue.Real -> PinValue.Float(v.value().toFloat())
        is SignalValue.Bool -> PinValue.Bool(v.value())
        is SignalValue.Vec3 -> v.toVector3d().let { PinValue.Vec3(it.x, it.y, it.z) }
        is SignalValue.Quaternion -> PinValue.Quat(v.x(), v.y(), v.z(), v.w())
        else -> null
    }

    /** Coerce an incoming [PinValue] to the port's declared signal type —
     *  numeric-ish pins interconvert freely (our house rule for foreign
     *  writes), everything else must match shape. Null = undeliverable. */
    private fun toSignalValue(type: SignalType, v: PinValue): SignalValue? = when (type) {
        SignalType.REAL -> when (v) {
            is PinValue.Float -> SignalValue.Real(v.value.toDouble())
            is PinValue.Int -> SignalValue.Real(v.value.toDouble())
            is PinValue.Redstone -> SignalValue.Real(v.value.toDouble())
            is PinValue.Bool -> SignalValue.Real(if (v.value) 1.0 else 0.0)
            else -> null
        }
        SignalType.BOOLEAN -> when (v) {
            is PinValue.Bool -> SignalValue.Bool(v.value)
            is PinValue.Float -> SignalValue.Bool(v.value != 0f)
            is PinValue.Int -> SignalValue.Bool(v.value != 0)
            is PinValue.Redstone -> SignalValue.Bool(v.value > 0)
            else -> null
        }
        SignalType.VEC3 -> (v as? PinValue.Vec3)?.let { SignalValue.Vec3(it.x, it.y, it.z) }
        SignalType.QUATERNION -> (v as? PinValue.Quat)?.let {
            SignalValue.Quaternion(it.x, it.y, it.z, it.w)
        }
        else -> null
    }

    /** Port over a REGISTERED Cimulink endpoint: schema from the directory
     *  record, IO through gameServices (GAME_TICK domain), same as the CC
     *  connector's registered path. */
    private class RuntimePort(
        private val serverLevel: ServerLevel,
        private val record: PlantRecord,
    ) : PinPort {

        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            runCatching { record.schema().outputs() }.getOrDefault(emptyList()).mapNotNull { def ->
                pinTypeOf(def.type())?.let { LinkPin(PREFIX + def.name(), it, def.name()) }
            }

        override fun pinInputs(ctx: LinkContext): List<LinkPin> =
            runCatching { record.schema().inputs() }.getOrDefault(emptyList()).mapNotNull { def ->
                pinTypeOf(def.type())?.let { LinkPin(PREFIX + def.name(), it, def.name()) }
            }

        override fun readPin(id: String): PinReading? {
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return null
            val value = runCatching {
                services().readPlantOutput(ExecutionDomain.GAME_TICK, record.endpointId(), name)
            }.getOrNull() ?: return null
            return toPinValue(value)?.let { PinReading(it) }
        }

        override fun writePin(id: String, value: PinValue) {
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return
            val def = runCatching { record.schema().input(name) }.getOrNull() ?: return
            val signal = toSignalValue(def.type(), value) ?: return
            runCatching {
                services().writePlantInput(
                    ExecutionDomain.GAME_TICK, record.endpointId(), name, signal, serverLevel.gameTime,
                )
            }
        }

        override fun clearPin(id: String) {
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return
            val def = runCatching { record.schema().input(name) }.getOrNull() ?: return
            val fallback = def.defaultValue() ?: return
            runCatching {
                services().writePlantInput(
                    ExecutionDomain.GAME_TICK, record.endpointId(), name, fallback, serverLevel.gameTime,
                )
            }
        }

        private fun services() =
            CimulinkWorldRuntimes.forLevel(serverLevel).runtime().gameServices()
    }

    private class SynaxisPort(private val be: BlockEntity, private val plant: PlantPort) : PinPort {

        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            runCatching { plant.schema().outputs() }.getOrDefault(emptyList()).mapNotNull { def ->
                pinTypeOf(def.type())?.let { LinkPin(PREFIX + def.name(), it, def.name()) }
            }

        override fun pinInputs(ctx: LinkContext): List<LinkPin> =
            runCatching { plant.schema().inputs() }.getOrDefault(emptyList()).mapNotNull { def ->
                pinTypeOf(def.type())?.let { LinkPin(PREFIX + def.name(), it, def.name()) }
            }

        override fun readPin(id: String): PinReading? {
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return null
            val value = runCatching { readOutput(name) }.getOrNull() ?: return null
            return toPinValue(value)?.let { PinReading(it) }
        }

        override fun writePin(id: String, value: PinValue) {
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return
            val def = runCatching { plant.schema().input(name) }.getOrNull() ?: return
            val signal = toSignalValue(def.type(), value) ?: return
            runCatching { applyInput(name, signal) }
        }

        override fun clearPin(id: String) {
            // Unlinked input → back to the port's declared default (a motor
            // target parks at 0 instead of latching the last delivered value).
            val name = id.removePrefix(PREFIX).takeIf { id.startsWith(PREFIX) } ?: return
            val def = runCatching { plant.schema().input(name) }.getOrNull() ?: return
            def.defaultValue()?.let { runCatching { applyInput(name, it) } }
        }

        /** GAME_TICK path first (our engine runs on the server tick), physics-
         *  safe read as fallback — same dual dispatch the CC connector uses. */
        private fun readOutput(name: String): SignalValue? = when {
            plant is GameThreadPlantPort && plant.supportsDomain(ExecutionDomain.GAME_TICK) ->
                plant.readOutput(name)
            plant is PhysicsSafePlantPort && plant.supportsDomain(ExecutionDomain.PHYSICS_SUBSTEP_READONLY) ->
                plant.readPhysicsOutput(name)
            else -> null
        }

        private fun applyInput(name: String, value: SignalValue) {
            when {
                plant is GameThreadPlantPort && plant.supportsDomain(ExecutionDomain.GAME_TICK) ->
                    plant.applyInput(name, value)
                plant is PhysicsSafePlantPort && plant.supportsDomain(ExecutionDomain.PHYSICS_SUBSTEP_CONTROL) ->
                    plant.writePhysicsInput(name, value, be.level?.gameTime ?: 0L)
            }
        }
    }
}
