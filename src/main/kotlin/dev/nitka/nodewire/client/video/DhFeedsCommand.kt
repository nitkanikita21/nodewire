package dev.nitka.nodewire.client.video

import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent

/**
 * `/nodewire dhfeeds [off|low|medium|high|full]` — Distant Horizons LOD mode
 * for camera feeds (see [DistantHorizonsCompat.Mode]). Bare command reports
 * the current mode. Only registered when DH is installed (this class touches
 * [DistantHorizonsCompat], which references DH API types).
 */
object DhFeedsCommand {

    fun register(event: RegisterClientCommandsEvent) {
        val root = Commands.literal("dhfeeds")
            .executes { ctx ->
                ctx.source.sendSystemMessage(
                    Component.literal("DH in camera feeds: ${DistantHorizonsCompat.mode.name.lowercase()}"),
                )
                1
            }
        for (m in DistantHorizonsCompat.Mode.entries) {
            root.then(
                Commands.literal(m.name.lowercase()).executes { ctx ->
                    DistantHorizonsCompat.setMode(m)
                    val hint = when (m) {
                        DistantHorizonsCompat.Mode.OFF -> "LODs skipped in feeds"
                        DistantHorizonsCompat.Mode.FULL -> "LODs in feeds at your DH quality"
                        else -> "LODs in feeds, global quality override: ${m.name.lowercase()}"
                    }
                    ctx.source.sendSystemMessage(Component.literal("DH in camera feeds: ${m.name.lowercase()} ($hint)"))
                    1
                },
            )
        }
        event.dispatcher.register(Commands.literal("nodewire").then(root))
    }
}
