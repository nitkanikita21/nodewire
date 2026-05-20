package dev.nitka.nodewire.integration.aeronautics

import net.minecraft.world.level.block.entity.BlockEntity

/**
 * Aeronautics block-entity kind addressed by an [AeroChannel] binding.
 *
 * Class references resolve via reflection at runtime (Aeronautics ships as
 * a JarJar wrapper, so no direct compile-time class access). [matches]
 * walks the class hierarchy so subclasses of an Aero BE still match — this
 * matters if Aeronautics ships variant subclasses now or in the future.
 *
 * Safe to load when Aeronautics is absent: nothing here touches Aero
 * classes until [matches] is invoked against a candidate BE, which the
 * pipeline gates behind a mod-loaded check.
 */
enum class AeroBlockKind(
    val displayName: String,
    val fqn: String,
) {
    SMART_PROPELLER(
        "Smart Propeller",
        "dev.eriksonn.aeronautics.content.blocks.propeller.small.smart_propeller.SmartPropellerBlockEntity",
    ),
    ANDESITE_PROPELLER(
        "Andesite Propeller",
        "dev.eriksonn.aeronautics.content.blocks.propeller.small.andesite.AndesitePropellerBlockEntity",
    ),
    WOODEN_PROPELLER(
        "Wooden Propeller",
        "dev.eriksonn.aeronautics.content.blocks.propeller.small.wooden.WoodenPropellerBlockEntity",
    ),
    HOT_AIR_BURNER(
        "Hot Air Burner",
        "dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity",
    ),
    STEAM_VENT(
        "Steam Vent",
        "dev.eriksonn.aeronautics.content.blocks.hot_air.steam_vent.SteamVentBlockEntity",
    ),
    MOUNTED_POTATO_CANNON(
        "Mounted Potato Cannon",
        "dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon.MountedPotatoCannonBlockEntity",
    ),
    PROPELLER_BEARING(
        "Propeller Bearing",
        "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity",
    );

    /**
     * True if [be]'s runtime class (or any superclass) matches this
     * entry's [fqn]. Walks the hierarchy so subclasses still match —
     * e.g. SmartPropellerBlockEntity is itself a subclass of
     * BasePropellerBlockEntity, and if Aeronautics ever ships a further
     * subclass we still resolve.
     */
    fun matches(be: BlockEntity): Boolean {
        var c: Class<*>? = be::class.java
        while (c != null) {
            if (c.name == fqn) return true
            c = c.superclass
        }
        return false
    }

    companion object {
        fun fromBE(be: BlockEntity): AeroBlockKind? =
            entries.firstOrNull { it.matches(be) }

        fun fromName(name: String): AeroBlockKind? =
            entries.firstOrNull { it.name == name }
    }
}
