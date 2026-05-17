package dev.nitka.nodewire.integration.tweakedcontroller

import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraftforge.fml.ModList
import java.lang.reflect.Method
import java.util.UUID

/**
 * Soft-dependency wrapper for the Create: Tweaked Controllers mod
 * (`create_tweaked_controllers` on Forge). All TC access goes through
 * reflection so Nodewire builds and runs with TC absent. Every public
 * method degrades gracefully — returns false / null / a zero state —
 * when TC isn't loaded or the cached class lookup fails.
 *
 * Class names confirmed from a jar spike against TC 1.20.1-1.2.6:
 *   * Controller item: TweakedLinkedControllerItem
 *   * Server state holders: TweakedLinkedControllerServerHandler.receivedInputs / .receivedAxes
 */
object TweakedController {

    const val MOD_ID: String = "create_tweaked_controllers"

    private val controllerItemClass: Class<*>? by lazy {
        try {
            Class.forName("com.getitemfromblock.create_tweaked_controllers.item.TweakedLinkedControllerItem")
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * TC's Lectern Controller BlockEntity. The lectern holds a controller
     * item and exposes [lecternGetButton] / [lecternGetAxis] for reading
     * gamepad state of whoever currently sits on it. We locate it by
     * scanning loaded chunks around a Logic Block at evaluation time.
     */
    private val lecternBeClass: Class<*>? by lazy {
        try {
            Class.forName("com.getitemfromblock.create_tweaked_controllers.block.TweakedLecternControllerBlockEntity")
        } catch (_: Throwable) {
            null
        }
    }

    private val lecternGetController: Method? by lazy {
        lecternBeClass?.let { runCatching { it.getMethod("getController") }.getOrNull() }
    }

    private val lecternGetButton: Method? by lazy {
        lecternBeClass?.let { runCatching { it.getMethod("GetButton", Int::class.javaPrimitiveType) }.getOrNull() }
    }

    private val lecternGetAxis: Method? by lazy {
        lecternBeClass?.let { runCatching { it.getMethod("GetAxis", Int::class.javaPrimitiveType) }.getOrNull() }
    }

    /** Half-side length of the chunk scan box, in blocks. */
    private const val LECTERN_SEARCH_RADIUS_BLOCKS = 64

    /** True iff TC mod is loaded into the current runtime. */
    fun isLoaded(): Boolean = ModList.get()?.isLoaded(MOD_ID) ?: false

    /**
     * Nodewire's own NBT key for a per-item-instance UUID. TC's controller
     * item has no inherent UUID — it identifies channels by configurable
     * frequencies (Create redstone-link style), not per-instance ids.
     * We mint our own UUID on first lookup and persist it on the stack so
     * the binding model ("this specific controller" ↔ "this logic block")
     * has something stable to point at. The UUID is opaque to TC and only
     * meaningful inside Nodewire.
     */
    private const val NW_BIND_ID_KEY = "nw:bindId"

    /**
     * Returns a stable identifier for the controller represented by [stack],
     * or `null` if the stack isn't a TC controller item. Allocates and
     * persists a UUID into the stack's NBT on first call — subsequent
     * calls return the same id. Caller must hold the stack on the server
     * side (item-NBT mutations sync to the client via vanilla item slot
     * sync). The reflection class check guards against accidentally tagging
     * non-controller stacks.
     */
    fun controllerItemId(stack: ItemStack): UUID? {
        if (!isLoaded() || stack.isEmpty) return null
        val klass = controllerItemClass ?: return null
        if (!klass.isInstance(stack.item)) return null
        val tag = stack.orCreateTag
        if (tag.hasUUID(NW_BIND_ID_KEY)) return tag.getUUID(NW_BIND_ID_KEY)
        val minted = UUID.randomUUID()
        tag.putUUID(NW_BIND_ID_KEY, minted)
        return minted
    }

    /**
     * Fetch the current input state for the controller [id], or `null`
     * when TC isn't loaded, no matching lectern is in range, or no player
     * is sitting on the lectern.
     *
     * Strategy: scan loaded chunks within [LECTERN_SEARCH_RADIUS_BLOCKS]
     * of [origin] for a TweakedLecternControllerBlockEntity whose held
     * controller stack carries our `nw:bindId` matching [id]. Reads
     * button/axis state directly off that BE via reflection — the BE
     * receives state from the seated player's gamepad packets.
     *
     * Performance: per-tick chunk scan. With one Logic Block and a few
     * lecterns it's fine; if it becomes a hotspot, add a level-scoped
     * UUID → BlockPos cache invalidated on lectern load/unload.
     */
    fun getControllerState(level: Level, origin: BlockPos, id: UUID): ControllerState? {
        if (!isLoaded()) return null
        val lecternClass = lecternBeClass ?: return null
        val getController = lecternGetController ?: return null
        val getButton = lecternGetButton ?: return null
        val getAxis = lecternGetAxis ?: return null

        val r = LECTERN_SEARCH_RADIUS_BLOCKS
        val minChunkX = (origin.x - r) shr 4
        val maxChunkX = (origin.x + r) shr 4
        val minChunkZ = (origin.z - r) shr 4
        val maxChunkZ = (origin.z + r) shr 4

        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for ((_, be) in chunk.blockEntities) {
                    if (!lecternClass.isInstance(be)) continue
                    val stack = (getController.invoke(be) as? ItemStack) ?: continue
                    if (stack.isEmpty) continue
                    val tag = stack.tag ?: continue
                    if (!tag.hasUUID(NW_BIND_ID_KEY)) continue
                    if (tag.getUUID(NW_BIND_ID_KEY) != id) continue
                    return readLecternState(be, getButton, getAxis)
                }
            }
        }
        return null
    }

    /**
     * GLFW gamepad index map. Verified against TC 1.20.1-1.2.6's
     * `TweakedLecternControllerBlockEntity.GetButton(int)` /
     * `.GetAxis(int)` (which consume the same indices).
     *
     * Buttons: 0=A, 1=B, 2=X, 3=Y, 4=LB, 5=RB, 6=Back, 7=Start,
     *          9=LStickClick, 10=RStickClick, 11=DPadUp, 12=DPadRight,
     *          13=DPadDown, 14=DPadLeft.  (8 = Guide, unused by Nodewire.)
     *
     * Axes: 0=LStickX, 1=LStickY, 2=RStickX, 3=RStickY, 4=LT, 5=RT.
     */
    private fun readLecternState(be: Any, getButton: Method, getAxis: Method): ControllerState {
        fun btn(i: Int): Boolean = runCatching { getButton.invoke(be, i) as Boolean }.getOrElse { false }
        fun axis(i: Int): Float = runCatching { getAxis.invoke(be, i) as Float }.getOrElse { 0f }
        return ControllerState(
            leftStickX = axis(0),
            leftStickY = axis(1),
            rightStickX = axis(2),
            rightStickY = axis(3),
            leftTrigger = axis(4),
            rightTrigger = axis(5),
            buttonA = btn(0),
            buttonB = btn(1),
            buttonX = btn(2),
            buttonY = btn(3),
            leftBumper = btn(4),
            rightBumper = btn(5),
            back = btn(6),
            start = btn(7),
            leftStickClick = btn(9),
            rightStickClick = btn(10),
            dpadUp = btn(11),
            dpadRight = btn(12),
            dpadDown = btn(13),
            dpadLeft = btn(14),
        )
    }
}

/**
 * Plain data class holding one snapshot of a controller's gamepad
 * inputs. Stick axes in [−1f, 1f] (positive Y is "up"), triggers in
 * [0f, 1f], buttons boolean. Defaults are all-zero / all-false so the
 * evaluator never reads NaN.
 */
data class ControllerState(
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val buttonA: Boolean = false,
    val buttonB: Boolean = false,
    val buttonX: Boolean = false,
    val buttonY: Boolean = false,
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    val leftStickClick: Boolean = false,
    val rightStickClick: Boolean = false,
    val start: Boolean = false,
    val back: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
) {
    companion object {
        val ZERO = ControllerState()
    }
}
