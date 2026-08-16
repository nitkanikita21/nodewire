package dev.nitka.nodewire.client.video

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam

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
 * The pin is applied lazily from DH's own before-render event (DhApi.Delayed
 * isn't populated until DH finishes init). If pinning ever fails (API drift),
 * we fall back to Vista's NO_LODS behaviour — cancel DH's pass during
 * captures — so the flicker can't come back either way.
 *
 * IMPORTANT: this class references DH API types (compileOnly dep) — it must
 * only be LOADED behind a `ModList.isLoaded("distanthorizons")` gate
 * ([dev.nitka.nodewire.client.NodewireClient] does).
 */
object DistantHorizonsCompat {

    fun setup() {
        DhApiEventRegister.on(DhApiBeforeRenderEvent::class.java, LodStabilizer())
    }

    private class LodStabilizer : DhApiBeforeRenderEvent() {
        private var pinned = false
        private var attemptsLeft = 200 // ~10 s of frames before giving up

        override fun beforeRender(param: DhApiCancelableEventParam<DhApiRenderParam>) {
            if (!pinned && attemptsLeft > 0) {
                attemptsLeft--
                pinned = runCatching {
                    DhApi.Delayed.configs.graphics()
                        .useCameraPositionForQualityDropOff().setValue(false)
                }.getOrDefault(false)
            }
            // Fallback (pin unavailable): keep feeds flicker-free the blunt
            // way — no LODs inside captures.
            if (!pinned && VideoManager.isCapturing()) param.cancelEvent()
        }
    }
}
