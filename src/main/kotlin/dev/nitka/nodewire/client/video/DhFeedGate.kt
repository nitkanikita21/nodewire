package dev.nitka.nodewire.client.video

/**
 * Bridge between [dev.nitka.nodewire.mixin.camera.MixinDhClientApi] (which
 * must not reference any DH or compat classes — it loads whenever the mixin
 * config does) and [DistantHorizonsCompat] (which only loads when DH is
 * installed).
 *
 * The mixin HEAD-cancels ALL of DH's render entries (`renderLods`,
 * `renderDeferredLodsForShaders`, `renderFadeOpaque`, `renderFadeTransparent`)
 * while a capture pass is running — the DH API's cancellable event covers only
 * the LOD draw, while the FADE passes (the vanilla-chunk blend zone around the
 * viewer — exactly the flickering region observed) have no cancellable event
 * at all. Killing them at the source keeps every scrap of DH per-pass state
 * (overdraw-cutout centre, fade zone, matrices) anchored to the PLAYER's pass
 * only.
 */
object DhFeedGate {

    /** Set by [DistantHorizonsCompat] when the user opts into LODs-in-feeds
     *  (`/nodewire dhfeeds low|medium|high|full`). Default false = DH never
     *  renders inside captures. */
    @JvmStatic
    @Volatile
    var renderLodsInFeeds: Boolean = false

    /** The mixin's single question: should this DH render call be skipped? */
    @JvmStatic
    fun skipDhRender(): Boolean = VideoManager.isCapturing() && !VideoManager.isExternalCapture() && !renderLodsInFeeds
}
