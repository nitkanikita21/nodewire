package dev.nitka.nodewire.client.script

import dev.nitka.nodewire.client.wire.ClientLogicBlockTracker
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/**
 * Phase 2c — CLIENT frame driver for script `clientBehavior {}` code.
 *
 * Subscribed to ONE [RenderLevelStageEvent] stage ([DRIVER_STAGE]); per client
 * frame it does the cheap single-owner rendezvous for every loaded script node:
 * snapshot the node's replicated-in tag, advance its frame clock, drain its
 * `log()`-out. The behavior BODIES never run on the render thread — they run on
 * the worker pool ([dev.nitka.nodewire.script.ScriptDispatchers]); the render
 * thread only advances clocks + copies buffers (hardening #1). A wall-clock
 * per-resume budget ([ClientScriptNodeRuntime.resumeBudgetMs]) disables a CPU
 * runaway fast without ever blocking the frame.
 *
 * Kill-switch (hardening #3): [ClientScriptToggle.enabled] is read at the TOP of
 * every frame. When OFF, all client runtimes are cancelled + the frame is a
 * no-op — reachable WITHOUT rendering, via the `/nodewire clientscripts off`
 * client command.
 */
object ClientScriptDriver {

    /** The single render stage we drive on. After translucent blocks matches the
     *  other Nodewire world renderers; any single stage is fine — we only need a
     *  once-per-frame callback, not a specific draw order (we draw nothing).
     *
     *  `by lazy` is LOAD-BEARING: touching [RenderLevelStageEvent.Stage] triggers
     *  a chain into vanilla [net.minecraft.core.registries.BuiltInRegistries]
     *  that isn't bootstrapped in plain unit tests. Deferring it off the class's
     *  static init keeps the testable gate logic ([tick]) loadable headless. */
    val DRIVER_STAGE: RenderLevelStageEvent.Stage by lazy {
        RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
    }

    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        // ONE stage only — guard against the event firing for every other stage
        // in the same frame (it dispatches once per stage per frame).
        if (event.stage != DRIVER_STAGE) return
        tick()
    }

    /**
     * One frame of client-script driving. Extracted from the event handler so
     * it's unit-testable without a [RenderLevelStageEvent] (the kill-switch gate
     * + the enumeration are the testable logic; the rendezvous itself is covered
     * by [ClientScriptRuntimeTest]).
     */
    fun tick() {
        // Hardening #3 — kill-switch gate, checked BEFORE any per-node work.
        if (!ClientScriptToggle.enabled) {
            // cancelAll already ran when the switch flipped OFF; harmless to keep
            // the frame a strict no-op here (no re-cancel churn).
            return
        }
        // Only drive while a client level is loaded.
        if (Minecraft.getInstance().level == null) return

        for (be in ClientLogicBlockTracker.all()) {
            for ((src, stateTag) in be.clientScriptNodes()) {
                ClientScriptNodeRuntime.frameTick(src, stateTag)
            }
        }
    }

    /** Level-unload / disconnect: cancel every client runtime. */
    fun onLevelUnload() {
        ClientScriptNodeRuntime.cancelAll()
    }
}
