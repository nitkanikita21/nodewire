package dev.nitka.nodewire.client.camera.harness

import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import java.nio.file.Files

/**
 * Which render entry the camera capture uses per feed:
 *
 *  * [Mode.LEGACY] — the nested `GameRenderer.renderLevel` call (the proven
 *    path; full GameRenderer side-effect surface).
 *  * [Mode.HARNESS] — [FeedRenderDriver]: direct `LevelRenderer.renderLevel`
 *    with our own camera/matrices and a balanced render stack.
 *
 * Switched with `/nodewire capture <legacy|harness>`, persisted in
 * `config/nodewire-client.properties`. Default LEGACY until the harness is
 * proven against the pack (Sodium+Iris+Veil+Flywheel+DH); flips in Phase 4.
 */
object CaptureEngine {

    enum class Mode { LEGACY, HARNESS }

    var mode: Mode = loadMode()
        private set

    /** Runtime bisection toggles (`/nodewire capture kick|nofog`), not persisted. */
    @JvmStatic
    @Volatile
    var kickEnabled: Boolean = true

    @JvmStatic
    @Volatile
    var noFogEnabled: Boolean = true

    fun setMode(m: Mode) {
        mode = m
        saveMode(m)
    }

    fun registerCommand(event: RegisterClientCommandsEvent) {
        val root = Commands.literal("capture")
            .executes { ctx ->
                ctx.source.sendSystemMessage(Component.literal("Capture engine: ${mode.name.lowercase()}"))
                1
            }
        for (m in Mode.entries) {
            root.then(
                Commands.literal(m.name.lowercase()).executes { ctx ->
                    setMode(m)
                    ctx.source.sendSystemMessage(Component.literal("Capture engine: ${m.name.lowercase()}"))
                    1
                },
            )
        }
        root.then(
            Commands.literal("dump").executes { ctx ->
                CaptureDebug.request()
                ctx.source.sendSystemMessage(
                    Component.literal(
                        "Capture debug armed: next capture logs GL state + saves feed PNGs to screenshots/nodewire-debug/",
                    ),
                )
                1
            },
        )
        // Bisection toggles for the residual no-shaders chunk blink.
        root.then(
            Commands.literal("kick").executes { ctx ->
                kickEnabled = !kickEnabled
                ctx.source.sendSystemMessage(Component.literal("Post-capture graph kick: " + if (kickEnabled) "ON" else "OFF"))
                1
            },
        )
        root.then(
            Commands.literal("nofog").executes { ctx ->
                noFogEnabled = !noFogEnabled
                ctx.source.sendSystemMessage(Component.literal("Feed setupNoFog: " + if (noFogEnabled) "ON" else "OFF"))
                1
            },
        )
        event.dispatcher.register(Commands.literal("nodewire").then(root))
    }

    // ── persistence (same properties file the DH mode uses) ───────────────

    private const val KEY = "capture_engine="

    private fun propsFile() = FMLPaths.CONFIGDIR.get().resolve("nodewire-client.properties")

    private fun loadMode(): Mode = runCatching {
        val f = propsFile()
        if (!Files.exists(f)) return Mode.LEGACY
        val line = Files.readAllLines(f).firstOrNull { it.startsWith(KEY) } ?: return Mode.LEGACY
        Mode.valueOf(line.substringAfter('=').trim().uppercase())
    }.getOrDefault(Mode.LEGACY)

    private fun saveMode(m: Mode) {
        runCatching {
            val f = propsFile()
            val rest = if (Files.exists(f)) Files.readAllLines(f).filterNot { it.startsWith(KEY) } else emptyList()
            Files.write(f, rest + (KEY + m.name))
        }
    }
}
