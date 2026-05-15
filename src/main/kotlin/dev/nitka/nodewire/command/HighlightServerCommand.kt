package dev.nitka.nodewire.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.logging.LogUtils
import dev.nitka.nodewire.net.HighlightPacket
import dev.nitka.nodewire.net.NodewireNetwork
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.network.PacketDistributor

/**
 * Server-side mirror of [dev.nitka.nodewire.client.command.HighlightCommand].
 *
 * Reason: chat-message `ClickEvent.RUN_COMMAND` actions send commands to
 * the server via the regular chat-command path, bypassing Forge's client
 * command dispatcher. Without this, the chat link posted by
 * `BindingsManagerScreen` returns "command not found".
 *
 * The handler runs server-side, then sends [HighlightPacket] back to the
 * executing player so the client's `BlockHighlightRenderer` does the
 * visual work. Direct typing still hits the client dispatcher first and
 * never reaches here.
 */
object HighlightServerCommand {
    private val LOG = LogUtils.getLogger()

    fun register(event: RegisterCommandsEvent) {
        LOG.info("Registering /nodewire highlight server command")
        event.dispatcher.register(
            Commands.literal("nodewire").then(
                Commands.literal("highlight").then(
                    Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes { ctx ->
                            val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                            val player = ctx.source.playerOrException
                            NodewireNetwork.CHANNEL.send(
                                PacketDistributor.PLAYER.with { player },
                                HighlightPacket(pos, DEFAULT_DURATION_MS),
                            )
                            1
                        }
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes { ctx ->
                                    val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                                    val secs = IntegerArgumentType.getInteger(ctx, "seconds")
                                    val player = ctx.source.playerOrException
                                    NodewireNetwork.CHANNEL.send(
                                        PacketDistributor.PLAYER.with { player },
                                        HighlightPacket(pos, secs * 1000L),
                                    )
                                    1
                                },
                        ),
                ),
            ),
        )
    }

    private const val DEFAULT_DURATION_MS = 3000L
}
