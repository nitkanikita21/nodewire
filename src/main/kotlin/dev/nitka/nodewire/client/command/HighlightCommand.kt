package dev.nitka.nodewire.client.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraftforge.client.event.RegisterClientCommandsEvent

object HighlightCommand {
    fun register(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("nodewire").then(
                Commands.literal("highlight").then(
                    Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes { ctx ->
                            val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                            BlockHighlightRenderer.highlight(pos)
                            1
                        }
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes { ctx ->
                                    val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                                    val secs = IntegerArgumentType.getInteger(ctx, "seconds")
                                    BlockHighlightRenderer.highlight(pos, secs * 1000L)
                                    1
                                },
                        ),
                ),
            ),
        )
    }
}
