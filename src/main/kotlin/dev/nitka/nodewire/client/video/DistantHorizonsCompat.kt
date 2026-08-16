package dev.nitka.nodewire.client.video

import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam

/**
 * Distant Horizons × camera capture. DH's renderer keeps temporal state that a
 * second render pass per frame (our camera FBO capture) corrupts — feeds and
 * even the main view flicker. The cure is the one Vista ships: register a DH
 * API [DhApiBeforeRenderEvent] listener and CANCEL DH's render while the pass
 * is a camera capture, so LODs simply don't draw inside feeds and DH's state
 * only ever advances on the real player view.
 *
 * IMPORTANT: this class references DH API types (compileOnly dep) — it must
 * only be LOADED behind a `ModList.isLoaded("distanthorizons")` gate
 * ([dev.nitka.nodewire.client.NodewireClient] does).
 */
object DistantHorizonsCompat {

    fun setup() {
        DhApiEventRegister.on(DhApiBeforeRenderEvent::class.java, SkipLodsInCapture())
    }

    private class SkipLodsInCapture : DhApiBeforeRenderEvent() {
        override fun beforeRender(param: DhApiCancelableEventParam<DhApiRenderParam>) {
            if (VideoManager.isCapturing()) param.cancelEvent()
        }
    }
}
