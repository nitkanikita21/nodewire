package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler
import dev.nitka.nodewire.ui.input.absoluteOffset
import dev.nitka.nodewire.ui.input.hitTest
import dev.nitka.nodewire.ui.layout.IntSize
import dev.nitka.nodewire.ui.modifier.input.OnHoverModifier
import dev.nitka.nodewire.ui.modifier.input.OnSizeChangedModifier
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
 *     pulse if there are frame waiters, runs Yoga `calculateLayout`, fires
 *     post-layout callbacks (`onSizeChanged`), then paints. Cheap when idle.
 *   * `dispatchPointer(event)` — call from Screen.mouse* overrides. Routes
 *     to the focus owner during a drag, otherwise hit-tests the tree.
 *   * `dispose()` — cancels the coroutine scope, disposes the composition,
 *     unregisters the snapshot observer. Must be called from `Screen.removed()`.
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

    /** Drag focus owner: set on Press, routed-to on Drag/Release, cleared on Release. */
    private var pointerFocus: UiNode? = null

    /** Currently-hovered nodes — kept so we can fire `OnHoverModifier(false)` on exit. */
    private val hoveredNodes = mutableSetOf<UiNode>()

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
        postLayoutWalk(root)
        root.renderWalk(canvas)
    }

    /**
     * Walks the tree after layout to fire `onSizeChanged` callbacks for
     * nodes whose computed size changed since last frame. First-frame fires
     * unconditionally (`lastSize == null`).
     */
    private fun postLayoutWalk(node: UiNode) {
        val currentSize = IntSize(node.layoutWidth, node.layoutHeight)
        for (mod in node.inputModifiers) {
            if (mod is OnSizeChangedModifier && mod.lastSize != currentSize) {
                mod.lastSize = currentSize
                mod.callback(currentSize)
            }
        }
        for (child in node.children) postLayoutWalk(child)
    }

    /**
     * Routes a pointer event into the tree. Returns `true` iff the event
     * was consumed (so the caller's `super` shouldn't run).
     *
     * Press: hit-test, remember the consuming node as drag focus.
     * Drag / Release: route directly to the focus owner (drag should stick
     *   to its source even when the pointer slides outside).
     * Move: hit-test for hover side-effects but never consume.
     * Scroll: hit-test, let the deepest handler consume.
     */
    fun dispatchPointer(event: PointerEvent): Boolean {
        return when (event) {
            is PointerEvent.Press -> {
                val hit = root.hitTest(event)
                pointerFocus = hit
                hit != null
            }
            is PointerEvent.Drag -> {
                val focus = pointerFocus ?: return false
                routeToFocus(focus, event)
            }
            is PointerEvent.Release -> {
                val focus = pointerFocus
                val handled = if (focus != null) routeToFocus(focus, event) else false
                pointerFocus = null
                handled
            }
            is PointerEvent.Move -> {
                updateHover(event)
                false // hover is observational, never consumes
            }
            is PointerEvent.Scroll -> root.hitTest(event) != null
        }
    }

    private fun routeToFocus(focus: UiNode, event: PointerEvent): Boolean {
        val (absX, absY) = focus.absoluteOffset()
        val localX = event.x - absX
        val localY = event.y - absY
        for (mod in focus.inputModifiers) {
            if (mod is PointerHandler && mod.handle(event, localX, localY)) return true
        }
        return false
    }

    /**
     * For a Move event: walk the tree to collect nodes currently under the
     * pointer that have an [OnHoverModifier]. Fire enter/exit callbacks for
     * the set-membership delta vs. last frame.
     *
     * Source of truth is [hoveredNodes] (a node-keyed set), NOT the
     * modifier instance's own flag — every recomposition that touches the
     * modifier chain (e.g. `.background(bg)` changing on hover state)
     * builds a fresh `OnHoverModifier` with `isHovered=false`, so checking
     * its flag would lose the previous hover state and skip the exit callback.
     */
    private fun updateHover(event: PointerEvent.Move) {
        val current = mutableSetOf<UiNode>()
        collectHoveredOnHoverNodes(root, event.x, event.y, 0, 0, current)
        // Exit
        for (node in hoveredNodes - current) {
            for (mod in node.inputModifiers) {
                if (mod is OnHoverModifier) mod.callback(false)
            }
        }
        // Enter
        for (node in current - hoveredNodes) {
            for (mod in node.inputModifiers) {
                if (mod is OnHoverModifier) mod.callback(true)
            }
        }
        hoveredNodes.clear()
        hoveredNodes.addAll(current)
    }

    private fun collectHoveredOnHoverNodes(
        node: UiNode,
        x: Int,
        y: Int,
        parentOffsetX: Int,
        parentOffsetY: Int,
        sink: MutableSet<UiNode>,
    ) {
        val absX = parentOffsetX + node.layoutX
        val absY = parentOffsetY + node.layoutY
        if (x !in absX until (absX + node.layoutWidth)) return
        if (y !in absY until (absY + node.layoutHeight)) return
        if (node.inputModifiers.any { it is OnHoverModifier }) sink.add(node)
        for (child in node.children) collectHoveredOnHoverNodes(child, x, y, absX, absY, sink)
    }

    fun dispose() {
        if (!running) return
        running = false
        snapshotHandle.dispose()
        composition.dispose()
        recomposer.close()
        scope.cancel()
        pointerFocus = null
        hoveredNodes.clear()
    }
}
