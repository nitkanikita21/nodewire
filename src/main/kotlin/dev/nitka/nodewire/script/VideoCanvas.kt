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
}
