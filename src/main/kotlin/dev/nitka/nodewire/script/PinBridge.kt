package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinValueConversion
import net.minecraft.nbt.CompoundTag

/**
 * Runtime bridge between the graph's [PinValue] world and the script-facing
 * Kotlin types. In-world only (it touches `graph.*` + `net.minecraft.*`).
 *
 * Conversion reuses [PinValueConversion.convert] for scalar coercion so an
 * `INT` wired into an `input<Float>` behaves like everywhere else in the
 * graph. Boxing/unboxing is by [PinSpec.type] / [StateCell.kind] — no
 * reflection on the script types.
 */

/** Host-side bridge from the sandbox-safe [ScriptType] to the graph's [PinType]. */
fun ScriptType.toPinType(): PinType = when (this) {
    ScriptType.BOOL -> PinType.BOOL
    ScriptType.INT -> PinType.INT
    ScriptType.FLOAT -> PinType.FLOAT
    ScriptType.REDSTONE -> PinType.REDSTONE
    ScriptType.STRING -> PinType.STRING
    ScriptType.VEC2 -> PinType.VEC2
    ScriptType.VEC3 -> PinType.VEC3
    ScriptType.QUAT -> PinType.QUAT
}

/** PinValue (graph) -> script-facing boxed value, stored into [ScriptModule.inputs]. */
internal fun ScriptModule.pushInputs(incoming: Map<String, PinValue>) {
    for ((name, spec) in specsIn) {
        val pinType = spec.type.toPinType()
        val raw = incoming[name]
        if (raw == null) {
            // Unconnected: keep the declared default if one was set, else the
            // type's zero-value (boxed to the script type).
            if (inputs[name] == null) inputs[name] = boxDefault(pinType)
            continue
        }
        val converted = PinValueConversion.convert(raw, pinType)
        inputs[name] = unbox(converted)
    }
}

/** script-facing outputs -> PinValue map. Unwritten outputs fill from [PinValue.default]. */
internal fun ScriptModule.pullOutputs(): Map<String, PinValue> {
    // Read the COMMITTED frame (the behaviors' last commit-at-suspend), falling
    // back to the live buffer when nothing has committed yet (first tick, or the
    // legacy tickBlock path that never commits). See ScriptModule.commitFrame.
    val frame = committedOutputs()
    val out = LinkedHashMap<String, PinValue>(specsOut.size)
    for ((name, spec) in specsOut) {
        val pinType = spec.type.toPinType()
        val written = frame[name]
        out[name] = if (written == null) PinValue.default(pinType) else box(written, pinType)
    }
    return out
}

/** Per [StateCell]: read the NBT slot keyed by the cell name, by [StateKind]. */
@Suppress("UNCHECKED_CAST")
internal fun ScriptModule.loadState(tag: CompoundTag) {
    for (cell in stateCells) {
        if (!tag.contains(cell.key)) continue
        val c = cell as StateCell<Any?>
        c.value = when (cell.kind) {
            StateKind.INT -> tag.getInt(cell.key)
            StateKind.FLOAT -> tag.getFloat(cell.key)
            StateKind.BOOL -> tag.getBoolean(cell.key)
            StateKind.STRING -> tag.getString(cell.key)
            StateKind.REDSTONE -> Redstone.of(tag.getInt(cell.key))
        }
    }
}

/** Per [StateCell]: write the cell value into the NBT slot keyed by the cell name. */
internal fun ScriptModule.saveState(tag: CompoundTag) {
    for (cell in stateCells) {
        when (cell.kind) {
            StateKind.INT -> tag.putInt(cell.key, cell.value as Int)
            StateKind.FLOAT -> tag.putFloat(cell.key, cell.value as Float)
            StateKind.BOOL -> tag.putBoolean(cell.key, cell.value as Boolean)
            StateKind.STRING -> tag.putString(cell.key, cell.value as String)
            StateKind.REDSTONE -> tag.putInt(cell.key, (cell.value as Redstone).power)
        }
    }
}

// ── value boxing helpers ────────────────────────────────────────────────

/** Unwrap a [PinValue] into the plain script-side type the [Input] handle returns. */
private fun unbox(v: PinValue): Any = when (v) {
    is PinValue.Bool -> v.value
    is PinValue.Int -> v.value
    is PinValue.Float -> v.value
    is PinValue.Redstone -> Redstone.of(v.value)
    is PinValue.Str -> v.value
    is PinValue.Vec2 -> Vec2(v.x, v.y)
    is PinValue.Vec3 -> Vec3(v.x, v.y, v.z)
    is PinValue.Quat -> Quat(v.x, v.y, v.z, v.w)
}

/**
 * Wrap a script-side value (whatever the script assigned to an [Output]) into
 * the [PinValue] for [type]. [Redstone] clamps defensively (the
 * `PinValue.Redstone` ctor does NOT clamp despite its kdoc).
 */
private fun box(value: Any?, type: PinType): PinValue = when (type) {
    PinType.BOOL -> PinValue.Bool(value as Boolean)
    PinType.INT -> PinValue.Int(value as Int)
    PinType.FLOAT -> PinValue.Float(value as Float)
    PinType.REDSTONE -> PinValue.Redstone((value as Redstone).power.coerceIn(0, 15))
    PinType.STRING -> PinValue.Str(value as String)
    PinType.VEC2 -> (value as Vec2).let { PinValue.Vec2(it.x, it.y) }
    PinType.VEC3 -> (value as Vec3).let { PinValue.Vec3(it.x, it.y, it.z) }
    PinType.QUAT -> (value as Quat).let { PinValue.Quat(it.x, it.y, it.z, it.w) }
    PinType.ANY -> value as PinValue
}

/** Script-side zero-value for an unconnected input, boxed to the script type. */
private fun boxDefault(type: PinType): Any = when (type) {
    PinType.BOOL -> false
    PinType.INT -> 0
    PinType.FLOAT -> 0f
    PinType.REDSTONE -> Redstone.OFF
    PinType.STRING -> ""
    PinType.VEC2 -> Vec2(0f, 0f)
    PinType.VEC3 -> Vec3(0f, 0f, 0f)
    PinType.QUAT -> Quat.IDENTITY
    PinType.ANY -> false
}
