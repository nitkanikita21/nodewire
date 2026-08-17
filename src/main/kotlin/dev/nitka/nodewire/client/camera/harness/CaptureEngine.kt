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

    enum class Mode { LEGACY, HARNESS, OFF }

    var mode: Mode = loadMode()
        private set

    /** Runtime bisection toggles (`/nodewire capture kick|nofog`), not persisted. */
    @JvmStatic
    @Volatile
    var kickEnabled: Boolean = true

    @JvmStatic
    @Volatile
    var noFogEnabled: Boolean = true

    /** Full Sodium freeze during captures. OFF by default: with the
     *  post-capture re-cull kick fixed (it used to be cancelled by our own
     *  freeze mixin), honest per-feed culling gives a far better feed picture
     *  and a stable main view. `/nodewire capture freecull` toggles back to
     *  the freeze (feeds then draw the player's visible set). */
    @JvmStatic
    @Volatile
    var fullFreeze: Boolean = false

    /** Bisection: feeds don't run Sodium's terrain setup (`nocull`). */
    @JvmStatic
    @Volatile
    var noFeedCull: Boolean = false

    /** Bisection: feeds don't draw terrain (`nodraw`). */
    @JvmStatic
    @Volatile
    var noFeedDraw: Boolean = false

    /** Bisection: feeds don't draw Sable sub-levels (`nosable`). */
    @JvmStatic
    @Volatile
    var noSableDraw: Boolean = false

    /** Bisection: feeds skip the TRANSLUCENT terrain pass (`notrans`). */
    @JvmStatic
    @Volatile
    var noFeedTranslucent: Boolean = false

    /** Bisection: feeds skip the OPAQUE terrain passes (`noopaque`). */
    @JvmStatic
    @Volatile
    var noFeedOpaque: Boolean = false

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
        root.then(
            Commands.literal("nocull").executes { ctx ->
                noFeedCull = !noFeedCull
                ctx.source.sendSystemMessage(
                    Component.literal("Feed terrain CULL: " + if (noFeedCull) "SKIPPED" else "normal"),
                )
                1
            },
        )
        root.then(
            Commands.literal("nodraw").executes { ctx ->
                noFeedDraw = !noFeedDraw
                ctx.source.sendSystemMessage(
                    Component.literal("Feed terrain DRAW: " + if (noFeedDraw) "SKIPPED" else "normal"),
                )
                1
            },
        )
        root.then(
            Commands.literal("notrans").executes { ctx ->
                noFeedTranslucent = !noFeedTranslucent
                ctx.source.sendSystemMessage(
                    Component.literal("Feed TRANSLUCENT terrain: " + if (noFeedTranslucent) "SKIPPED" else "normal"),
                )
                1
            },
        )
        root.then(
            Commands.literal("noopaque").executes { ctx ->
                noFeedOpaque = !noFeedOpaque
                ctx.source.sendSystemMessage(
                    Component.literal("Feed OPAQUE terrain: " + if (noFeedOpaque) "SKIPPED" else "normal"),
                )
                1
            },
        )
        root.then(
            Commands.literal("blink").executes { ctx ->
                CaptureDebug.armBlinkDiag(600)
                ctx.source.sendSystemMessage(
                    Component.literal("Blink sampler armed for 600 frames — reproduce the blink now, then check the log"),
                )
                1
            },
        )
        root.then(
            Commands.literal("nosable").executes { ctx ->
                noSableDraw = !noSableDraw
                ctx.source.sendSystemMessage(
                    Component.literal("Feed Sable sub-level DRAW: " + if (noSableDraw) "SKIPPED" else "normal"),
                )
                1
            },
        )
        root.then(
            Commands.literal("freecull").executes { ctx ->
                fullFreeze = !fullFreeze
                ctx.source.sendSystemMessage(
                    Component.literal(
                        if (fullFreeze) "Sodium full freeze: ON (feed uses player's visible set)"
                        else "Sodium full freeze: OFF (honest per-feed culling — may blink)",
                    ),
                )
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
