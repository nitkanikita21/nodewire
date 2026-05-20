package dev.nitka.nodewire.integration.aeronautics

import net.minecraft.world.level.block.entity.BlockEntity

/**
 * Aeronautics block-entity kind addressed by an [AeroChannel] binding.
 *
 * Class references resolve via reflection at runtime, only when an instance
 * check is performed ([matches]) or when an [AeroChannel.read] runs. Both
 * happen only after the pipeline's mod-loaded guard, so this enum is safe
 * to load even when Aeronautics is absent at runtime.
 */
enum class AeroBlockKind(val displayName: String) {
    SMART_PROPELLER("Smart Propeller") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.propeller.small.smart_propeller.SmartPropellerBlockEntity"
    },
    ANDESITE_PROPELLER("Andesite Propeller") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.propeller.small.andesite.AndesitePropellerBlockEntity"
    },
    WOODEN_PROPELLER("Wooden Propeller") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.propeller.small.wooden.WoodenPropellerBlockEntity"
    },
    HOT_AIR_BURNER("Hot Air Burner") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity"
    },
    STEAM_VENT("Steam Vent") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.hot_air.steam_vent.SteamVentBlockEntity"
    },
    MOUNTED_POTATO_CANNON("Mounted Potato Cannon") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon.MountedPotatoCannonBlockEntity"
    },
    PROPELLER_BEARING("Propeller Bearing") {
        override fun matches(be: BlockEntity): Boolean =
            be::class.qualifiedName == "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity"
    };

    abstract fun matches(be: BlockEntity): Boolean

    companion object {
        fun fromBE(be: BlockEntity): AeroBlockKind? =
            entries.firstOrNull { it.matches(be) }

        fun fromName(name: String): AeroBlockKind? =
            entries.firstOrNull { it.name == name }
    }
}
