package dev.nitka.nodewire.config

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Server-side mod config — `serverconfig/nodewire-server.toml`, stored per-world
 * and replicated to clients, so the world's admin owns these toggles.
 *
 * Create-style grouping + comments, but on plain NeoForge [ModConfigSpec] — it
 * deliberately does NOT extend Create's internal `ConfigBase`, so the config
 * works whether or not Create is installed.
 *
 * Registered in [dev.nitka.nodewire.Nodewire]'s init as `ModConfig.Type.SERVER`.
 */
object NodewireConfig {
    val SPEC: ModConfigSpec

    /** Cheat: let pin links drive cannon-mount aim directly (`setPitch`/`setYaw`),
     *  bypassing kinetic/redstone control. Off by default. */
    val pinDrivenCannonAim: ModConfigSpec.BooleanValue

    init {
        val b = ModConfigSpec.Builder()
        b.comment("Cheat / sandbox toggles — off by default.").push("cheats")
        pinDrivenCannonAim = b
            .comment(
                "Let Nodewire pin links drive cannon-mount aim directly (setPitch/setYaw),",
                "bypassing the mount's normal kinetic/redstone control. This is a cheat.",
            )
            .define("pinDrivenCannonAim", false)
        b.pop()
        SPEC = b.build()
    }
}
