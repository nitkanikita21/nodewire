package dev.nitka.nodewire.client.video

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files

/**
 * Distant Horizons × camera capture — LODs render INSIDE feeds, flicker-free.
 *
 * DH's flicker with a second render pass per frame comes from its
 * quality-drop-off re-anchoring to the active render camera: alternating
 * between the player and the camera position every frame thrashes its
 * temporal LOD state. The cure (lifted from Vista's LOD-enabled modes) is
 * `graphics().useCameraPositionForQualityDropOff() = false` — quality anchors
 * to the PLAYER only, so both passes draw the same stable LOD set and the
 * capture simply views it from elsewhere.
 *
 * [Mode] mirrors Vista's DH modes, switched with `/nodewire dhfeeds …`:
 *
 *  * OFF — DH's pass is CANCELLED during captures (no LODs in feeds);
 *  * LOW / MEDIUM / HIGH — the drop-off pin plus a `horizontalQuality` API
 *    override at that level. NOTE: like Vista, the override is a GLOBAL
 *    runtime override (DH builds ONE LOD mesh — per-pass quality does not
 *    exist), so it trades the player's own LOD quality for feed cost too;
 *  * FULL — the drop-off pin only, LODs at the user's own quality.
 *
 * DEFAULT IS OFF: our capture is a NESTED vanilla renderLevel, and letting DH
 * render inside it imbalances DH's LOD-section bookkeeping (world smears,
 * shimmer, FPS drops — Vista gets away with LODs in feeds only because it
 * ships its own level renderer). The LOD modes stay available as
 * experimental via `/nodewire dhfeeds`.
 *
 * All values go through the DH config API's override layer ([setValue] /
 * [clearValue]) — the user's saved DH config is never touched, and switching
 * modes restores whatever they had. Config application happens lazily inside
 * DH's own before-render event (DhApi.Delayed is empty until DH finishes
 * init); if the API pin ever fails (API drift) the handler behaves as OFF so
 * flicker can't return. The chosen mode persists in
 * `config/nodewire-client.properties`.
 *
 * IMPORTANT: this class references DH API types (compileOnly dep) — it must
 * only be LOADED behind a `ModList.isLoaded("distanthorizons")` gate
 * ([dev.nitka.nodewire.client.NodewireClient] does).
 */
object DistantHorizonsCompat {

    enum class Mode(val quality: EDhApiHorizontalQuality?) {
        OFF(null),
        LOW(EDhApiHorizontalQuality.LOW),
        MEDIUM(EDhApiHorizontalQuality.MEDIUM),
        HIGH(EDhApiHorizontalQuality.HIGH),
        FULL(null),
    }

    var mode: Mode = Mode.OFF
        private set

    /** True once the config overrides matching [mode] are in place. */
    private var applied = false
    private var attemptsLeft = 200 // ~10 s of frames before falling back

    fun setup() {
        mode = loadMode()
        DhApiEventRegister.on(DhApiBeforeRenderEvent::class.java, FeedLodHandler())
    }

    /** `/nodewire dhfeeds <mode>` — switch + persist. Takes effect next frame. */
    fun setMode(m: Mode) {
        mode = m
        applied = false
        attemptsLeft = 200
        saveMode(m)
    }

    private class FeedLodHandler : DhApiBeforeRenderEvent() {
        override fun beforeRender(param: DhApiCancelableEventParam<DhApiRenderParam>) {
            if (!applied && attemptsLeft > 0) {
                attemptsLeft--
                applied = runCatching {
                    val gfx = DhApi.Delayed.configs.graphics()
                    if (mode == Mode.OFF) {
                        gfx.useCameraPositionForQualityDropOff().clearValue()
                        gfx.horizontalQuality().clearValue()
                    } else {
                        gfx.useCameraPositionForQualityDropOff().setValue(false)
                        val q = mode.quality
                        if (q != null) gfx.horizontalQuality().setValue(q) else gfx.horizontalQuality().clearValue()
                    }
                    true
                }.getOrDefault(false)
            }
            // OFF by choice, or pin unavailable → keep feeds flicker-free the
            // blunt way: no LODs inside captures.
            if ((mode == Mode.OFF || !applied) && VideoManager.isCapturing()) param.cancelEvent()
        }
    }

    // ── tiny client-side persistence ──────────────────────────────────────

    private fun propsFile() = FMLPaths.CONFIGDIR.get().resolve("nodewire-client.properties")

    private fun loadMode(): Mode = runCatching {
        val f = propsFile()
        if (!Files.exists(f)) return Mode.OFF
        val line = Files.readAllLines(f).firstOrNull { it.startsWith("dh_feeds=") } ?: return Mode.OFF
        Mode.valueOf(line.substringAfter('=').trim().uppercase())
    }.getOrDefault(Mode.OFF)

    private fun saveMode(m: Mode) {
        runCatching {
            val f = propsFile()
            val rest = if (Files.exists(f)) {
                Files.readAllLines(f).filterNot { it.startsWith("dh_feeds=") }
            } else {
                emptyList()
            }
            Files.write(f, rest + "dh_feeds=${m.name}")
        }
    }
}
