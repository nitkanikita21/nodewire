package dev.nitka.nodewire.script

/**
 * Sandbox-facing 2D draw surface a `clientBehavior {}` `frame()` body uses to
 * draw into a [Video] handle's offscreen surface.
 *
 * **Why this lives in `dev.nitka.nodewire.script` (and not `client.video`):**
 * the script sandbox (`SandboxClassLoader`) allowlists exactly the
 * `dev.nitka.nodewire.script.` prefix (+ `graph.PinValue`). Everything under
 * `client.video.*` — `GlVideoSurface`, `TextureTarget`, `NwCanvas`,
 * `Minecraft`, `GuiGraphics`, `ResourceLocation` — is **DENY**'d at link time.
 * So the script can name *this* interface (and the [Video] handle), but can
 * never reach the GL-backed implementation, the bind/unbind dance, or any
 * engine type. The runtime hands the script a [VideoCanvas] **instance only**;
 * the impl is on the far side of the sandbox boundary.
 *
 * **Capability surface (vetted, pure-2D verbs only):** clear / rect / border /
 * line / text + size queries. **No `blit` / texture loading in v1** — pulling
 * arbitrary `ResourceLocation`s is a sandbox-escape + asset-DoS surface, cut
 * per spec (Finding F4).
 *
 * **Colors are packed ARGB in a `Long`** (top byte = alpha), never the mod's
 * `ui.render.Color` (that would leak a UI type onto the allowlist). The impl
 * narrows the low 32 bits to an `Int` ARGB.
 *
 * **All coordinates/sizes/text are clamped at the implementation choke point**
 * (`VideoDrawClamps`) to `[0, size]`, so a malicious script cannot pass a
 * 2-billion-px rect to provoke a huge GL op or allocation blow-up (Finding F5).
 * The clamps are part of the contract, not the caller's responsibility.
 */
interface VideoCanvas {
    /** Surface width in pixels (== the standard video size). */
    fun width(): Int

    /** Surface height in pixels (== the standard video size). */
    fun height(): Int

    /** Fill the whole surface with a solid [color] (packed ARGB in a `Long`). */
    fun clear(color: Long)

    /** Solid axis-aligned rectangle at (x, y) sized (w, h), packed-ARGB [color]. */
    fun rect(x: Int, y: Int, w: Int, h: Int, color: Long)

    /**
     * Stroke a [thickness]-px border inside the rect (x, y, w, h). [thickness]
     * is clamped to `[1, size]`.
     */
    fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long)

    /**
     * Thin filled line from (x1, y1) to (x2, y2). **v1: axis-aligned only** — a
     * non-axis-aligned request degrades to the bounding strip; true diagonal
     * lines are out of scope for v1.
     */
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long)

    /** Single-line [s] at (x, y), packed-ARGB [color]. Over-long text is truncated. */
    fun text(s: String, x: Int, y: Int, color: Long)

    /**
     * Pixel width [s] would occupy when drawn with [text]. The default is the
     * vanilla-font approximation (≈6 px/glyph) so non-GL implementations keep
     * working; the client impl overrides with real font metrics. Used by the
     * `ui {}` layout DSL to measure text leaves.
     */
    fun textWidth(s: String): Int = s.length * 6

    /** Line height of the font [text] draws with. */
    fun lineHeight(): Int = 9

    /**
     * Lay out + paint a declarative [dev.nitka.nodewire.script.ui.UiSpec] tree
     * (built by the [ui] DSL) onto this surface. Default no-op so
     * headless/test canvases stay trivial; the client impl routes to the Yoga
     * flexbox engine. The spec is pure data — the engine never crosses into
     * the script sandbox.
     */
    fun renderUi(root: dev.nitka.nodewire.script.ui.UiSpec) {}

    /**
     * Project a WORLD position onto this canvas through [video]'s camera —
     * returns canvas px (origin top-left, y-down; the same space every other
     * verb draws in) **assuming the feed is blitted full-canvas**
     * (`image(video)`), so the blit stretch and the projection cancel out.
     *
     * Null when: [video] is not a live camera feed on this client, the camera
     * pose is unresolvable, or the point is behind the camera. Points outside
     * the canvas DO return coordinates (may be negative / > size) so callers
     * can clamp them into edge markers. Use for world-locked overlays:
     * `project(cam.value, mobX, mobY, mobZ)?.let { border(it.x.toInt()-8, …) }`.
     */
    fun project(video: Video, x: Double, y: Double, z: Double): Vec2? = null

    /**
     * Mount a flexbox UI on this surface — the `ui {}` layout DSL (see
     * [dev.nitka.nodewire.script.ui.UiScope] for the vocabulary). The root is
     * a COLUMN filling the whole canvas; [pad]/[gap]/[justify]/[align]
     * configure it like any other container.
     *
     * A MEMBER (not a top-level extension) on purpose — same lesson as
     * `ScriptModule.state`: the in-game script compile classpath is a packed
     * `script-api.jar` where K2 resolves type references and members fine but
     * NOT top-level functions reachable only via a star-import (no
     * `.kotlin_module` route). A member resolves through the receiver type.
     */
    fun ui(
        pad: Int = 0,
        gap: Int = 0,
        justify: dev.nitka.nodewire.script.ui.Justify = dev.nitka.nodewire.script.ui.Justify.Start,
        align: dev.nitka.nodewire.script.ui.Align = dev.nitka.nodewire.script.ui.Align.Stretch,
        block: dev.nitka.nodewire.script.ui.UiScope.() -> Unit,
    ) {
        renderUi(dev.nitka.nodewire.script.ui.buildUiSpec(pad, gap, justify, align, block))
    }

    /**
     * Blit another [Video]'s current frame into this surface, scaled into the
     * rect (x, y, w, h). This is the only way to *pass through* a camera/stream
     * feed (e.g. an `input<Video>`) and then draw HUD on top of it.
     *
     * Drawing this surface into itself is ignored (no GL read-write feedback).
     * Unlike a raw `blit`, the source is a [Video] handle the script already
     * holds via a pin — never an arbitrary asset — so it stays inside the
     * sandbox (the F4 cut was about `ResourceLocation` loading, not video→video).
     * Coords/sizes are clamped like every other verb.
     */
    fun image(video: Video, x: Int, y: Int, w: Int, h: Int)

    /** Blit [video] to fill the whole surface. */
    fun image(video: Video) = image(video, 0, 0, width(), height())

    // ── timing helpers (real wall-clock; for FPS counters + time-based animation) ──

    /** Wall-clock seconds since this surface's PREVIOUS draw (0 on the first
     *  draw). Use it to advance animation per real time: `x += speed * dt()`. */
    fun dt(): Float = 0f

    /** Total wall-clock seconds since this surface was first drawn. */
    fun time(): Float = 0f

    /** How many times this surface has been drawn (monotonic frame index). */
    fun frames(): Long = 0L

    /** Convenience: this surface's redraw rate = `1 / dt()` (0 on the first
     *  draw). Note this is the SURFACE's update rate (capped by the runtime's
     *  cadence), not the client's raw render FPS. */
    fun fps(): Float = dt().let { if (it > 0f) 1f / it else 0f }
}
