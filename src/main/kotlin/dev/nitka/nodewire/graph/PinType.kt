package dev.nitka.nodewire.graph

/**
 * Closed enum of pin types. Connections require **exact** type match — no
 * implicit coercion (a user can place a `ToFloat` / `ToVec3` conversion
 * node when conversion is wanted; see MVP spec for the rationale).
 *
 * The enum's `name` doubles as the NBT serialization key — adding a new
 * type appends an entry; never reorder or rename existing ones without a
 * data-migration step.
 */
enum class PinType {
    BOOL,
    INT,
    FLOAT,
    STRING,
    VEC2,
    VEC3,
    QUAT;

    companion object {
        /** Defensive lookup — falls back to [BOOL] if the saved key is unknown (forward-compat load). */
        fun fromName(name: String): PinType =
            entries.firstOrNull { it.name == name } ?: BOOL
    }
}
