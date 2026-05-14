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
    /**
     * Vanilla-style redstone signal. Internally an int clamped 0..15;
     * separate from [INT] so connections must go through an explicit
     * conversion node — prevents accidental wiring of a free-range int
     * (e.g. a counter at 9000) into a redstone-emitting pin.
     */
    REDSTONE,
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
