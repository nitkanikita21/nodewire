package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.Direction
import java.util.WeakHashMap

/**
 * `IPeripheral` exposed by every [LogicBlockEntity] face. CC: Tweaked
 * calls the `@LuaFunction`-annotated methods. Equality is `(be, side)`
 * identity so CC's peripheral cache works correctly.
 *
 * Attached computers are tracked in a [WeakHashMap] so a missed
 * `detach` doesn't leak a `IComputerAccess` reference. Per-attach
 * `needsInitialSync` flag is set on attach and cleared by the first
 * dispatch — the BE consults it when building the prev/new snapshot.
 */
class NodewirePeripheral(
    val be: LogicBlockEntity,
    val side: Direction,
) : IPeripheral {

    /** Map<computer, needsInitialSync>. Read under `attached.synchronized`. */
    internal val attached: MutableMap<IComputerAccess, Boolean> = WeakHashMap()

    override fun getType(): String = "nodewire"

    override fun equals(other: IPeripheral?): Boolean =
        other is NodewirePeripheral && other.be === be && other.side == side

    override fun attach(computer: IComputerAccess) {
        synchronized(attached) { attached[computer] = true }
        be.nwAttachPeripheral(this)
    }

    override fun detach(computer: IComputerAccess) {
        synchronized(attached) { attached.remove(computer) }
        if (synchronized(attached) { attached.isEmpty() }) {
            be.nwDetachPeripheral(this)
        }
    }

    /** Snapshot the current attachment set for safe iteration during dispatch. */
    internal fun attachmentsSnapshot(): List<Pair<IComputerAccess, Boolean>> =
        synchronized(attached) { attached.entries.map { it.key to it.value } }

    internal fun clearInitialSync(computer: IComputerAccess) {
        synchronized(attached) { attached[computer] = false }
    }
}
