package dev.nitka.nodewire.client.command

import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent

/**
 * `/nodewire aim` — logs what the crosshair is actually on, once every half
 * second for five seconds.
 *
 * Needed because a left click that produces no event at all is impossible to
 * tell apart from a click that lands on something else: the Control Panel sits
 * on a Sable sub-level, where block positions are plot coordinates far from
 * the player, and the panel has no collision shape, so what the game considers
 * targeted is worth reading directly rather than inferring.
 */
object AimCommand {

    private val LOG = LogUtils.getLogger()

    private var samplesLeft = 0
    private var lastSample = 0L

    fun register(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("nodewire").then(
                Commands.literal("gizmo").executes { ctx ->
                    val on = !dev.nitka.nodewire.client.camera.CameraGizmoState.enabled
                    dev.nitka.nodewire.client.camera.CameraGizmoState.enabled = on
                    ctx.source.sendSystemMessage(
                        Component.literal("Camera gizmo: " + if (on) "on (lens, view ray, axes)" else "off"),
                    )
                    1
                },
            ).then(
                Commands.literal("aim").executes { ctx ->
                    samplesLeft = 10
                    ctx.source.sendSystemMessage(
                        Component.literal("Aim at the panel — logging the crosshair target for 5 seconds"),
                    )
                    1
                },
            ),
        )
    }

    /** Call from the client tick. */
    fun tick() {
        if (samplesLeft <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastSample < 500L) return
        lastSample = now
        samplesLeft -= 1

        val mc = Minecraft.getInstance()
        val hit = mc.hitResult
        val text = when {
            hit == null -> "hitResult = null"
            hit.type == HitResult.Type.MISS -> "hitResult = MISS"
            hit is BlockHitResult ->
                "block ${mc.level?.getBlockState(hit.blockPos)?.block?.descriptionId} " +
                    "@ ${hit.blockPos.toShortString()} face=${hit.direction} loc=${hit.location}"
            hit is EntityHitResult -> "entity ${hit.entity.type.description.string}"
            else -> "hitResult = ${hit.type}"
        }
        LOG.info("[NW-AIM] {}", text)
    }
}
