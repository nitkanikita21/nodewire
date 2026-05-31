package dev.nitka.nodewire.client.script

import com.mojang.logging.LogUtils
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent

/**
 * Hardening #3 — `/nodewire clientscripts <on|off>` CLIENT command, flipping the
 * [ClientScriptToggle] kill-switch. Registered as a CLIENT command (runs on the
 * command thread, NOT inside the render loop) so a viewer can disable all
 * auto-running script `clientBehavior {}` code WITHOUT first rendering a frame.
 *
 * `off` also cancels every live client runtime immediately (via the toggle), so
 * nothing is left parked. Bare `/nodewire clientscripts` reports the state.
 */
object ClientScriptCommand {
    private val LOG = LogUtils.getLogger()

    fun register(event: RegisterClientCommandsEvent) {
        LOG.info("Registering /nodewire clientscripts client command")
        event.dispatcher.register(
            Commands.literal("nodewire").then(
                Commands.literal("clientscripts")
                    .executes { ctx ->
                        val state = if (ClientScriptToggle.enabled) "on" else "off"
                        ctx.source.sendSystemMessage(Component.literal("Nodewire client scripts: $state"))
                        1
                    }
                    .then(
                        Commands.literal("on").executes { ctx ->
                            ClientScriptToggle.set(true)
                            ctx.source.sendSystemMessage(Component.literal("Nodewire client scripts: ON"))
                            1
                        },
                    )
                    .then(
                        Commands.literal("off").executes { ctx ->
                            ClientScriptToggle.set(false)
                            ctx.source.sendSystemMessage(Component.literal("Nodewire client scripts: OFF (all client runtimes cancelled)"))
                            1
                        },
                    ),
            ),
        )
    }
}
