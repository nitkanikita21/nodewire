package dev.nitka.nodewire.script

/**
 * A persisted state slot. The delegated property's **name** becomes the NBT
 * key (mirroring how stock tick nodes persist via getInt/putInt). [kind]
 * selects which NBT accessor the host uses when loading/saving.
 */
class StateCell<T>(val key: String, var value: T, val kind: StateKind, val replicated: Boolean = false)

/** The NBT-persistable allowlist for `state(...)` cells. */
enum class StateKind { INT, FLOAT, BOOL, STRING, REDSTONE, VIDEO }
