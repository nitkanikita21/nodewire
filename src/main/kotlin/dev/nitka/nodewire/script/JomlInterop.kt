package dev.nitka.nodewire.script

import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Quaternionfc
import org.joml.Vector2d
import org.joml.Vector2dc
import org.joml.Vector2fc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3fc

/**
 * JOML ↔ script-value interop. `org.joml.*` is sandbox-allowlisted and on the
 * script compile classpath (plus a default import), so scripts can use real
 * JOML math directly:
 *
 * ```
 * val dir = target.value.toJoml().sub(origin.value.toJoml()).normalize()
 * yawOut.value = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()
 * aimOut.value = dir.toVec3()
 * ```
 *
 * Pins/state carry the plain [Vec2]/[Vec3]/[Quat] data classes — DOUBLE
 * precision end to end, so [toJoml] maps to JOML's `*d` types and world-scale
 * coordinates survive the boundary. Converters from the float variants
 * (`Vector3fc` etc. — what Minecraft itself hands around) are kept so
 * existing scripts keep compiling.
 *
 * Top-level extensions resolve off the packed script-api.jar because the jar
 * ships the `.kotlin_module` map (see scriptApiJar in scripting/build).
 */

fun Vec2.toJoml(): Vector2d = Vector2d(x, y)

fun Vec3.toJoml(): Vector3d = Vector3d(x, y, z)

fun Quat.toJoml(): Quaterniond = Quaterniond(x, y, z, w)

fun Vector2dc.toVec2(): Vec2 = Vec2(x(), y())

fun Vector3dc.toVec3(): Vec3 = Vec3(x(), y(), z())

fun Quaterniondc.toQuat(): Quat = Quat(x(), y(), z(), w())

fun Vector2fc.toVec2(): Vec2 = Vec2(x(), y())

fun Vector3fc.toVec3(): Vec3 = Vec3(x(), y(), z())

fun Quaternionfc.toQuat(): Quat = Quat(x(), y(), z(), w())
