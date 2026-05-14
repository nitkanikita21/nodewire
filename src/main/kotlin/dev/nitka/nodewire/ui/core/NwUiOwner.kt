package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.nitka.nodewire.ui.canvas.CanvasModifier
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.KeyFocusController
import dev.nitka.nodewire.ui.input.KeyHandler
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler
import dev.nitka.nodewire.ui.input.absoluteOffset
import dev.nitka.nodewire.ui.input.hitTest
import dev.nitka.nodewire.ui.layout.IntSize
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.modifier.input.OnHoverModifier
import dev.nitka.nodewire.ui.modifier.input.OnPositionedModifier
import dev.nitka.nodewire.ui.modifier.input.OnSizeChangedModifier
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.renderWalk
import dev.nitka.nodewire.ui.scroll.ScrollAxis
import dev.nitka.nodewire.ui.scroll.ScrollModifier
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

    /**
     * Holder of keyboard focus. State-backed so widgets can observe their
     * own focused-ness purely through [KeyFocusController.isFocused] during
     * composition — no need for a local mirror state, which had a race in
     * the first iteration (DisposableEffect runs after composition, so a
     * naive "if not registered yet, drop focus" check fired before the
     * handler was registered and instantly killed the focus).
     */
    private var keyFocus: KeyHandler? by mutableStateOf(null)

    val keyFocusController: KeyFocusController = object : KeyFocusController {
        override fun request(handler: KeyHandler) { keyFocus = handler }
        override fun release(handler: KeyHandler) {
            if (keyFocus === handler) keyFocus = null
        }
        override fun isFocused(handler: KeyHandler): Boolean = keyFocus === handler
    }

    /**
     * Screen size read by the composition (via `LocalScreenSize`). Updated
     * each [frame] so popup / overlay code can position relative to the
     * window without poking `Minecraft.getInstance()`.
     */
    val screenSize: MutableState<IntSize> = mutableStateOf(IntSize.Zero)

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
        val size = IntSize(w, h)
        if (screenSize.value != size) screenSize.value = size
        root.yoga.calculateLayout(w.toFloat(), h.toFloat())
        postLayoutWalk(root, 0, 0)
        root.renderWalk(canvas)
    }

    /**
     * After layout, walk the tree firing `onSizeChanged` and `onPositioned`
     * callbacks for nodes whose geometry changed since last frame. Each
     * modifier tracks its own last-seen value so callbacks only fire on
     * change; the first frame after composition always fires (state is null).
     */
    private fun postLayoutWalk(node: UiNode, parentScreenX: Int, parentScreenY: Int) {
        val screenX = parentScreenX + node.layoutX
        val screenY = parentScreenY + node.layoutY
        val size = IntSize(node.layoutWidth, node.layoutHeight)
        val coords = LayoutCoordinates(screenX, screenY, size.width, size.height)
        for (mod in node.inputModifiers) {
            when (mod) {
                is OnSizeChangedModifier -> if (mod.lastSize != size) {
                    mod.lastSize = size
                    mod.callback(size)
                }
                is OnPositionedModifier -> if (mod.lastCoords != coords) {
                    mod.lastCoords = coords
                    mod.callback(coords)
                }
                is CanvasModifier -> mod.state.advance()
                is ScrollModifier -> {
                    // contentSize = furthest child edge along the scroll axis.
                    // maxValue is the deepest the user can scroll before content
                    // would leave the viewport entirely.
                    val contentSize = if (mod.axis == ScrollAxis.Vertical) {
                        node.children.maxOfOrNull { it.layoutY + it.layoutHeight } ?: 0
                    } else {
                        node.children.maxOfOrNull { it.layoutX + it.layoutWidth } ?: 0
                    }
                    val viewport = if (mod.axis == ScrollAxis.Vertical) node.layoutHeight else node.layoutWidth
                    mod.state.maxValue = (contentSize - viewport).coerceAtLeast(0)
                    // Frame tick for the easing animation.
                    mod.state.advance()
                }
            }
        }
        for (child in node.children) postLayoutWalk(child, screenX, screenY)
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
                // Clear keyboard focus on any press; if the press lands on
                // a TextInput, its handler re-requests focus during hit-
                // testing and the slot is restored before we return.
                keyFocus = null
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
                // Also dispatch Move to handlers — they can side-effect on
                // cursor movement (e.g. dragging a "sticky" wire that follows
                // the mouse without a button held). hitTest returns null if
                // no handler claims it; either way we never consume Move
                // (it's broadcast, not click-like).
                root.hitTest(event)
                false
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

        // Match HitTester: only enforce parent bounds when the node clips
        // (canvas / scroll). Otherwise descend so overflow children stay
        // hover-detectable — same rationale as in HitTester.
        val scrolls = node.inputModifiers.filterIsInstance<ScrollModifier>()
        val scrollX = scrolls.firstOrNull { it.axis == ScrollAxis.Horizontal }?.state?.value ?: 0
        val scrollY = scrolls.firstOrNull { it.axis == ScrollAxis.Vertical }?.state?.value ?: 0
        val canvasMod = node.inputModifiers.filterIsInstance<CanvasModifier>().firstOrNull()
        val clipsToBounds = canvasMod != null || scrolls.isNotEmpty()
        if (clipsToBounds) {
            if (x !in absX until (absX + node.layoutWidth)) return
            if (y !in absY until (absY + node.layoutHeight)) return
        }

        val inOwnBounds = x in absX until (absX + node.layoutWidth)
            && y in absY until (absY + node.layoutHeight)
        if (inOwnBounds && node.inputModifiers.any { it is OnHoverModifier }) sink.add(node)
        if (canvasMod != null) {
            // Same inverse transform as HitTester: hand children world-space
            // coords, with their parent offset reset to zero.
            val z = canvasMod.state.zoom
            val px = canvasMod.state.panX
            val py = canvasMod.state.panY
            val worldX = ((x - absX) / z - px).toInt()
            val worldY = ((y - absY) / z - py).toInt()
            for (child in node.children) {
                collectHoveredOnHoverNodes(child, worldX, worldY, 0, 0, sink)
            }
        } else {
            for (child in node.children) {
                collectHoveredOnHoverNodes(child, x, y, absX - scrollX, absY - scrollY, sink)
            }
        }
    }

    /**
     * Routes a key event to the focused [KeyHandler] (if any). Returns
     * the handler's `handle` result so MC knows whether the keystroke
     * was consumed.
     */
    fun dispatchKey(event: KeyEvent): Boolean = keyFocus?.handle(event) ?: false

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
