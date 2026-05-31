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
enum class PinType(
    /**
     * True for a concrete type that can be carried by a channel / picked in a
     * type selector. The single source of truth for "selectable types" — a new
     * concrete type (e.g. [VIDEO]) shows up everywhere [CHANNEL_TYPES] is used
     * with no per-UI list edit. [ANY] is the wildcard, not a concrete channel
     * type, so it is NOT routable.
     */
    val routable: Boolean = true,
) {
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
    QUAT,
    /** Opaque video stream handle (a UUID). Frames are client-local, keyed by the handle. */
    VIDEO,
    /**
     * Generic — accepts a connection from any other pin type. Carries
     * the raw [PinValue] through the evaluator unchanged. The connect-UI
     * never rejects an ANY-end edge. See PinValueConversion (added in
     * Task 2) for the implicit-conversion rules used everywhere ANY is
     * NOT involved.
     */
    ANY(routable = false);

    companion object {
        /**
         * All concrete (routable) types, in declaration order — the dynamic
         * registry every type selector / channel picker should use instead of a
         * hardcoded list, so new types appear automatically.
         */
        val CHANNEL_TYPES: List<PinType> = entries.filter { it.routable }

        /** Defensive lookup — falls back to [BOOL] if the saved key is unknown (forward-compat load). */
        fun fromName(name: String): PinType =
            entries.firstOrNull { it.name == name } ?: BOOL

        /**
         * String codec — encodes as the enum's [name]; decode defends with
         * [fromName] which falls back to BOOL on unknown values, preserving
         * the project's forward-compat-load rule.
         */
        val CODEC: com.mojang.serialization.Codec<PinType> =
            com.mojang.serialization.Codec.STRING.xmap(::fromName, PinType::name)
    }
}
