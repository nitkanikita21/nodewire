package dev.nitka.nodewire.client.camera.harness

import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import java.nio.file.Files

/**
 * Client controls for the camera capture loop.
 *
 * Feeds are rendered by Vista (see [VistaFeedBridge]); all that is left to
 * configure here is whether captures run at all, plus a one-shot diagnostic
 * dump. `/nodewire capture <on|off|dump>`, the on/off state persisted in
 * `config/nodewire-client.properties`.
 */
object CaptureEngine {

    var enabled: Boolean = loadEnabled()
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        saveEnabled(value)
    }

    /** Feed refresh rate (per second). Every feed render is a second pass over
     *  the level, and this pack's renderers show a brief chunk artifact when
     *  that happens, so the rate is the one honest lever: fewer renders, fewer
     *  artifacts, choppier feeds. */
    var fps: Int = loadFps()
        private set

    fun setFps(value: Int) {
        fps = value.coerceIn(1, 30)
        saveFps(fps)
    }

    fun registerCommand(event: RegisterClientCommandsEvent) {
        val root = Commands.literal("capture")
            .executes { ctx ->
                val renderer = if (VistaFeedBridge.available()) "Vista" else "none (install Vista)"
                ctx.source.sendSystemMessage(
                    Component.literal(
                        "Camera captures: " + (if (enabled) "on" else "off") +
                            " | " + fps + " fps | renderer: " + renderer,
                    ),
                )
                1
            }
            .then(
                Commands.literal("on").executes { ctx ->
                    setEnabled(true)
                    ctx.source.sendSystemMessage(Component.literal("Camera captures: on"))
                    1
                },
            )
            .then(
                Commands.literal("off").executes { ctx ->
                    setEnabled(false)
                    ctx.source.sendSystemMessage(Component.literal("Camera captures: off"))
                    1
                },
            )
            .then(
                Commands.literal("fps").then(
                    Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 30))
                        .executes { ctx ->
                            setFps(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"))
                            ctx.source.sendSystemMessage(Component.literal("Camera feed rate: $fps fps"))
                            1
                        },
                ),
            )
            .then(
                Commands.literal("dump").executes { ctx ->
                    CaptureDebug.request()
                    ctx.source.sendSystemMessage(
                        Component.literal("Next capture will save feed PNGs to screenshots/nodewire-debug/"),
                    )
                    1
                },
            )
        event.dispatcher.register(Commands.literal("nodewire").then(root))
    }

    // ── persistence ───────────────────────────────────────────────────────

    private const val KEY = "captures_enabled="
    private const val FPS_KEY = "capture_fps="

    private fun propsFile() = FMLPaths.CONFIGDIR.get().resolve("nodewire-client.properties")

    private fun loadEnabled(): Boolean = runCatching {
        val f = propsFile()
        if (!Files.exists(f)) return true
        val line = Files.readAllLines(f).firstOrNull { it.startsWith(KEY) } ?: return true
        line.substringAfter('=').trim().toBooleanStrict()
    }.getOrDefault(true)

    private fun loadFps(): Int = runCatching {
        val f = propsFile()
        if (!Files.exists(f)) return 24
        val line = Files.readAllLines(f).firstOrNull { it.startsWith(FPS_KEY) } ?: return 24
        line.substringAfter('=').trim().toInt().coerceIn(1, 30)
    }.getOrDefault(24)

    private fun saveFps(value: Int) {
        runCatching {
            val f = propsFile()
            val rest = if (Files.exists(f)) Files.readAllLines(f).filterNot { it.startsWith(FPS_KEY) } else emptyList()
            Files.write(f, rest + (FPS_KEY + value))
        }
    }

    private fun saveEnabled(value: Boolean) {
        runCatching {
            val f = propsFile()
            val rest = if (Files.exists(f)) Files.readAllLines(f).filterNot { it.startsWith(KEY) } else emptyList()
            Files.write(f, rest + (KEY + value))
        }
    }
}
