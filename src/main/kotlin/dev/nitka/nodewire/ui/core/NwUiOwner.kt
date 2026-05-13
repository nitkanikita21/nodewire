package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.renderWalk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns one Composition for one Screen. Holds the root UiNode, the Recomposer,
 * a single-threaded dispatcher pinned to the client thread, and a
 * BroadcastFrameClock that doubles as the "frame waiting" flag.
 *
 * Lifecycle:
 *   * `start(content)` — launches the recomposer coroutine and installs the
 *     initial composition. Idempotent.
 *   * `frame(canvas, w, h)` — call once per MC render tick. Sends a frame
 *     pulse if there are frame waiters, runs Yoga `calculateLayout`, then
 *     paints. Cheap when idle.
 *   * `dispose()` — cancels the coroutine scope, disposes the composition,
 *     unregisters the snapshot observer. Must be called from `Screen.removed()`.
 *
 * One owner per Screen. Independent owners have independent state — closing
 * screen A doesn't affect screen B's composition.
 */
class NwUiOwner {

    val root = UiNode()

    private var hasFrameWaiters = false
    private val clock = BroadcastFrameClock { hasFrameWaiters = true }

    private val dispatcher = NwClientDispatcher()
    private val scope = CoroutineScope(dispatcher + clock + SupervisorJob())

    private val recomposer = Recomposer(scope.coroutineContext)
    private val composition = Composition(NwApplier(root), recomposer)

    /**
     * Snapshot writes from anywhere on any thread schedule a single
     * `sendApplyNotifications()` on the dispatcher. Coalescing prevents
     * one-snapshot-per-mutation thrash when a frame mutates many states.
     */
    private var applyScheduled = false
    private val snapshotHandle = Snapshot.registerGlobalWriteObserver {
        if (!applyScheduled) {
            applyScheduled = true
            scope.launch {
                applyScheduled = false
                Snapshot.sendApplyNotifications()
            }
        }
    }

    private var running = false

    fun start(content: @Composable () -> Unit) {
        if (running) return
        running = true
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent { content() }
        // Force the first frame so initial layout happens before render.
        hasFrameWaiters = true
    }

    /**
     * One render-tick of the UI. Called from Screen.render with `w`/`h` set
     * to the current screen size (which can change between frames if the
     * window resizes; Yoga handles that fine).
     */
    fun frame(canvas: NwCanvas, w: Int, h: Int) {
        if (hasFrameWaiters) {
            hasFrameWaiters = false
            clock.sendFrame(System.nanoTime())
        }
        root.yoga.calculateLayout(w.toFloat(), h.toFloat())
        root.renderWalk(canvas)
    }

    fun dispose() {
        if (!running) return
        running = false
        snapshotHandle.dispose()
        composition.dispose()
        recomposer.close()
        scope.cancel()
    }
}
