# Video data type + dynamic-screen block — prior-art research

> Research-only. No mod code written. Goal: pick the right MODEL for a future Nodewire
> "video" data type + "dynamic screen" block, given two hard constraints:
> 1. **Frames cannot flow through a per-tick scalar `PinValue`.** Nodewire's `PinValue`
>    (`graph/PinValue.kt`) is a sealed class of small scalars — `Bool/Int/Float/Redstone/
>    Str/Vec2/Vec3/Quat` — serialized every tick via a `Codec.STRING.dispatch` keyed on a
>    `type` field. A pixel buffer per tick per pin is impossible.
> 2. **Rendering is client-side; the graph is server-side.** The graph is evaluated
>    server-side in `LogicBlockEntity.serverTick`; the UI is a custom Compose-runtime
>    client renderer. Block/camera STATE is server-authoritative; pixels can only ever be
>    realized on the client render thread.
>
> Date: 2026-05-30. Target platform: Minecraft 1.21.1 / NeoForge 21.1.230 / Kotlin.
> **Version note:** all rendering API signatures below are SPECIFICALLY 1.21.1. They change
> hard at 1.21.2 and again at 1.21.5 (see §3.5). Sources are quoted with real file paths;
> the four anchor references (OC `TextBuffer`, Tom's Peripherals `MonitorBlockEntity`,
> Vista `LiveFeedTexturesManager`, Vista `gradle.properties`) were re-read from source and
> cross-checked, not just taken from the findings.

---

## 1. OpenComputers: how a programmable display is modelled (and why it is a character grid, not video)

**Repo:** `MightyPirates/OpenComputers` (the thorough public source is the MC 1.7.10 Scala
line, sha `571482db`; the data model is version-stable, the client render path is not).

### 1.1 Two-layer component split — the program never touches pixels

A program calls methods on a **GPU** component (server-side
`src/main/scala/li/cil/oc/server/component/GraphicsCard.scala`) that is `bind()`-ed by
network address to a **Screen** component (server-side
`src/main/scala/li/cil/oc/common/component/TextBuffer.scala`). The GPU holds **no pixels** —
it is a stateless controller carrying the current fg/bg colour, palette selection,
active-buffer index, and per-tier cost tables. All cell data lives in the bound screen's
`util.TextBuffer`. **This is the load-bearing precedent for Nodewire: what flows between
components is a small handle (a screen network address + an integer active-buffer index),
never the buffer contents.**

### 1.2 The data model is a CHARACTER GRID

Confirmed by reading `src/main/scala/li/cil/oc/util/TextBuffer.scala`. The class holds exactly
two parallel 2-D arrays sized `[height][width]`:

```scala
var color  = Array.fill(height, width)(packed) // packed Short: (deflate(fg)<<8)|deflate(bg)
var buffer = Array.fill(height, width)(0x20)    // one Unicode CODEPOINT per cell (init = space)
```

There is **no per-pixel RGB plane anywhere**. The unit of storage and mutation is the **cell**
(a glyph + two packed colours). The op set (all `@Callback`, most `direct=true`, in
`GraphicsCard.scala`) is `set(x,y,string[,vertical])` / `get` / `fill(x,y,w,h,char)` /
`copy(x,y,w,h,tx,ty)` (the in-`util.TextBuffer.copy` walks the target in the safe direction so
overlaps don't corrupt) / `setForeground|Background` / `get|setPaletteColor` / `setDepth` /
`setResolution` / `setViewport` / `bind`. Wide (CJK) chars take 2 cells via `FontUtils.wcwidth`.

### 1.3 Indexed colour keeps a cell to 1 byte — the trick that makes it serializable

`src/main/scala/li/cil/oc/util/PackedColor.scala`: `OneBit = SingleBitFormat` (mono);
`FourBit = MutablePaletteFormat` (16 editable entries); `EightBit = HybridFormat` (16 editable
+ a fixed 240-entry RGB cube = 256). Arbitrary RGB is snapped to the nearest index by
weighted-luma distance (`0.2126/0.7152/0.0722`) in `deflate()` — **lossy by design**. So
"8-bit colour" = 1 byte index into a 16-edit+240-fixed table, never 24-bit truecolour.

### 1.4 Resolution caps are a feature, not a limitation

`Settings.scala`: `screenResolutionsByTier = (50,16),(80,25),(160,50)`; depths `1/4/8-bit`.
The effective max is `min(gpu tier, screen tier)`. The **largest** screen is 160×50 = **8000
cells**; at 1 fg byte + 1 bg byte that is ~16 KB worst case for the whole buffer, and only
deltas move per tick (§2). This is exactly what keeps OC shippable.

### 1.5 VRAM buffers = additional grids addressed by integer handle

`src/main/scala/li/cil/oc/common/component/traits/VideoRamDevice.scala` gives each GPU a
`HashMap[Int, GpuTextBuffer]` (index 0 = `RESERVED_SCREEN_INDEX`, the bound screen).
`allocateBuffer([w,h])` returns an integer index into off-screen scratch grids;
`setActiveBuffer(i)` redirects all subsequent `set/fill/copy`. `bitblt(dst,col,row,w,h,
src,fromCol,fromRow)` (in `GpuTextBuffer.bitblt`, via `TextBuffer.rawcopy`, re-packing colour
across differing depths) transfers a rectangle between any two pages. **vram→vram is ~free;
vram→screen is the only metered path** (cost = `bitbltCost * 2^tier`, scaled by dirty area,
with `LimitReachedException` + `context.pause` back-pressure). Pattern: assemble a frame in an
off-screen page, then commit it to the screen in one cheap call — double-buffering, and cost
scales with **changes**, not buffer size.

### 1.6 Why this is NOT video (the boundary Nodewire must own)

- The finest addressable unit is a **glyph cell**, not a pixel.
- The famous OC "pixel graphics" are **faked** with the 2×4 Unicode-**braille** trick
  (`U+2800..U+28FF`): each cell encodes up to 8 sub-dots (libs like `drawille`), giving an
  effective 320×200 monochrome-per-cell bitmap on a 160×50 T3 screen — but sub-dots share the
  cell's single fg/bg, and it is hand-rolled in Lua, **not** a GPU primitive.
- No frame timing / codec / streaming, no truecolour, no per-pixel alpha. Updates are
  program-driven mutations, not a decoded stream.
- Direct synchronous GPU→screen writes force a **0.1 s computer pause at world save** so the
  saved screen and saved computer agree (the big comment atop `GraphicsCard.scala`).

**Conclusion:** OC is the right blueprint for a **low-res, indexed-colour, capped, delta-synced
"screen buffer" data type** — but it is explicitly NOT a model for true video. True video needs
an out-of-band frame/texture channel referenced only by handle (§2, §3, §4).

---

## 2. OpenComputers' server→client sync model — the part Nodewire should copy literally

This is the single most reusable idea in the whole study. Sources:
`common/component/TextBuffer.scala`, `server/PacketSender.scala`, `client/PacketHandler.scala`,
`client/renderer/TextBufferRenderCache.scala`.

1. **Server authoritative, client mirror.** `common.component.TextBuffer` picks a `ServerProxy`
   on the server, `ClientProxy` on the client (via `SideTracker`). The authoritative grid lives
   server-side; the client keeps a replayed `util.TextBuffer` mirror.
2. **Mutations are captured as COMMANDS, not state diffs.** Every mutator on `ServerProxy`
   (`onBufferSet/Fill/Copy/ColorChange/DepthChange/ResolutionChange/PaletteChange/BitBlt/
   RamInit/RamDestroy`) appends a compact sub-command to one per-tick
   `CompressedPacketBuilder(PacketType.TextBufferMulti)` (node address written once). A `fill`
   is ~5 ints; a `set` is `(x,y,string)`; a `bitblt` is `(col,row,w,h,owner,id,fromCol,fromRow)`
   — **a handle + rectangle, never pixels**, because the page was synced once via
   `appendTextBufferRamInit`.
3. **One batched flush per tick.** `TextBuffer.update()` runs every tick and does
   `_pendingCommands.foreach(_.sendToPlayersNearHost(host, range²)); _pendingCommands = None`.
   **Bandwidth scales with how much CHANGED, not with screen size**, and is range-limited to
   nearby players + gzipped.
4. **Client decode = command replay.** `client.PacketHandler.onTextBufferMulti` loops
   `while(true){ readPacketType() match { Set => buffer.set(...); Fill => ...; Copy => ...;
   BitBlt => ... } }` until `EOFException`, re-running the **same** `util.TextBuffer` mutators
   to reconstruct identical state, then sets a single `dirty` flag.
5. **Full-snapshot + self-healing resync.** A fresh/late/relocated client sends
   `sendTextBufferInit`; the server replies with the entire buffer as NBT
   (`util.TextBuffer.save`: width/height/depth/palette + one `NBTTagString` per row + a packed
   `colors` short-array). A client-side `syncCooldown` re-requests every `syncInterval = 100`
   ticks until `markInitialized()`.
6. **Client render is dirty-flag-gated and cached.** `TextBufferRenderCache.render`: if dirty,
   rebuild a GL display list (`glNewList(GL_COMPILE_AND_EXECUTE)`); else `glCallList` — one
   cheap GPU call for an unchanged screen, independent of content size.
   `ScreenRenderer` (the in-world `TileEntitySpecialRenderer`) only renders the **origin** block
   of a multiblock, applies distance²/back-face culling + a distance fade, and short-circuits
   when `relativeLitArea == 0`.

**Determinism caveat:** command-replay requires bit-identical mutators on both sides (rounding,
font width, palette). Divergence desyncs silently — periodic full-snapshot reconciliation is the
backstop. **API caveat:** GL display lists + `glBegin/glEnd` `GL_QUADS` are gone in 1.21 core
profile (§3.5) — the *principle* (build once on dirty, replay cheaply) carries; the API does not.

---

## 3. Minecraft 1.21.1 / NeoForge 21.1.230 primitives: off-screen render → dynamic texture → block face

### 3.1 Three real-mod display models, on a spectrum of how "real" the frame is

| Model | Reference mod (verified) | Source of pixels | Where pixels live | Fit for many screens |
|---|---|---|---|---|
| **A. Live world re-render → FBO** | **Vista** (`MehVahdJukaar/cameramod`, MC 1.21.1 / NeoForge 21.1.228); also SecurityCraft `1.21.1` | re-invoke `LevelRenderer.renderLevel` from a swapped camera into a per-feed `RenderTarget` | client `RenderTarget` color attachment | poor — ~(N+1)× level-render cost; needs adaptive throttle |
| **B. Low-res pixel buffer → `DynamicTexture`** | **Tom's Peripherals** (`tom5454/Toms-Peripherals`, NeoForge 1.21.x) | graph/program writes an `int[]` ARGB buffer | client `DynamicTexture`+`NativeImage`; buffer also rides vanilla BE-sync NBT | good — dirty-flagged, ~0 cost when unchanged |
| **C. External-producer texture handle** | WebDisplays (Forge 1.12; via MCEF/CEF) | a Chromium process owns a GL texture | client GL texture id; mod only samples it | good per-screen but heavyweight per producer |
| (cheap escape hatch) Vanilla map | Image2Map (Fabric 1.21.x) | one-shot quantize to vanilla map palette → `FILLED_MAP` | vanilla map-data sync | static only; rides vanilla, zero ongoing cost |

**Cross-cutting rule observed in EVERY live-feed mod:** camera/screen STATE (pose, url,
resolution, links, on/off, ownership) lives + syncs server-side as small NBT/control packets;
the PIXELS are produced and held client-side. None stream full frames server→client every tick.
The only mod that puts pixels on the wire at all (Tom's) does so via **vanilla BE-sync,
dirty-flagged, capped at 64×64**.

### 3.2 Model A — capture the live world (Vista, the gold-standard reference)

Verified `gradle.properties`: `mc_version = 1.21.1`, `neoforge_version = 21.1.228` — the **same
MC+NeoForge line as Nodewire** (21.1.230), so it is a drop-in-compatible blueprint.

`common/.../client/renderer/VistaLevelRenderer.java#render(LiveFeedTexture, ViewFinderBlockEntity)`:
(1) save `mc.getMainRenderTarget()` and swap in `text.getRenderTarget()`; (2) replace
`mc.gameRenderer.mainCamera` with a reusable `DummyCamera`; (3) place a dummy display entity at
the camera block's center, set yaw/pitch from `EntityAngles.fromQuaternion(tile.getWorldOrientation(partialTicks))`;
(4) `canvas.bindWrite(true); RenderSystem.viewport(...); RenderSystem.clear(...); FogRenderer.setupNoFog()`;
(5) call vanilla `mc.levelRenderer.renderLevel(deltaTracker, false, camera, gameRenderer,
gameRenderer.lightTexture(), cameraMatrix, projMatrix)`. State isolation is the hard part: each
feed owns its own `LevelRendererCameraState` (occlusion graph / `visibleSections`) +
`RenderSystemState`, applied before and copied back after, with a `finally` restoring main
target, camera, post-effect, render distance. A `setupRender` mixin reroutes chunk culling while
`isRenderingLiveFeed()`.

### 3.3 Model A — the HANDLE, verified

`common/.../client/textures/LiveFeedTexturesManager.java` (re-read from source):

```java
public static class Handle {
    public Handle(UUID uuid, Vec2i screenSize) {
        this.textureId = VistaMod.res("live_feed/" + uuid + "_" + screenSize.x() + "x" + screenSize.y());
        ...
    }
}
```

The wire-equivalent of a "video" value is a **`UUID`** (resolved to a feed via
`BroadcastManager.getInstance(level).getBroadcast(uuid)`); the heavy `RenderTarget` lives
client-side, keyed by that UUID at `screenSize * LIVE_FEED_RESOLUTION_SCALE`. Multiple TVs
sharing one UUID **reuse one capture**. **Throttle (verified):**

```java
AdaptiveUpdateScheduler.builder()
    .baseFps(UPDATE_FPS).minFps(MIN_UPDATE_FPS)
    .targetBudgetMs(THROTTLING_UPDATE_MS) // ~1.66ms = ~10% of a 60fps frame -> at most ~6fps drop
    .evictAfterTicks(20 * 5)              // stop updating feeds nobody references for 5s
    .guardTargetFps(60)                   // back off harder under 60fps
    .build();
```

Plus `texture.markReferenced(...)` reference-counting, `setUpdateNextTick`, and an explicit
recursion guard (`if (VistaLevelRenderer.isRenderingLiveFeed()) requiresUpdate = false`). This
adaptive scheduler is the single most important lesson for keeping a video type from tanking FPS.
SecurityCraft does the equivalent with **adaptive multiplexing**: `activeFramesPerMcFrame =
ceil(fpsCap*(numFeeds+1)/currentMcFps)`, round-robining which cameras render each frame — so
"max active cameras" is not a hard cap but amortized render time (more cameras → each updates
less often).

### 3.4 Model B — low-res buffer → DynamicTexture (Tom's Peripherals, verified)

Re-read `MonitorBlockEntity.java`. The `int[] screen` pixel buffer lives on the BlockEntity and
rides the **vanilla BE-sync path**:

```java
public int[] screen = new int[0]; public int width;
public void sync() { ... getLevel().sendBlockUpdated(getBlockPos(), state, state, 3); }
@Override public CompoundTag getUpdateTag(HolderLookup.Provider p) {
    CompoundTag tag = new CompoundTag(); tag.putIntArray("s", screen); tag.putShort("w", (short) width); return tag; }
@Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
@Override protected void loadAdditional(CompoundTag t, Provider p) {
    super.loadAdditional(t, p);
    if (t.contains("s")) { screen = t.getIntArray("s"); width = t.getShort("w");
        if (clientCache != null) clientCache.invalidate(); } }   // <- client dirty flag
```

The client side (`screen/TextureCacheImpl.java`) keeps a `DynamicTexture(16,16,true)` registered
with `TextureManager`, a `NativeImage`, and an `IntBuffer` that is a **direct view into the
NativeImage's pixel memory** (`MemoryUtil.memIntBuffer(image.pixels, w*h)`). On the dirty flag:
`buffer.rewind(); buffer.put(int[]); dynTex.upload()` (reallocate via
`TextureUtil.prepareImage(id,w,h)` only on resize). `MonitorBlockEntityRenderer` draws one quad
with `buffer.getBuffer(RenderType.entityTranslucent(tex))` — standard vanilla render type, no
custom shader. **Cap: 64×64 px per monitor (= 4096 ints ≈ 16 KB), synced only on explicit
`sync()` (GPU flush), never per tick.** This is the textbook "pixel array → DynamicTexture →
quad" pattern and the proof that a small buffer CAN cross the wire if hard-capped and
dirty-flushed.

> **This maps onto Nodewire's existing plumbing 1:1.** `LogicBlockEntity` already overrides
> `getUpdateTag(...) = saveWithoutMetadata(...)` and `getUpdatePacket() =
> ClientboundBlockEntityDataPacket.create(this)`, and calls `level.sendBlockUpdated(pos, state,
> state, ...)` from `serverTick`. A screen sink that writes a capped buffer into its BE and
> dirty-syncs is the same path Tom's uses.

### 3.5 The 1.21.1 primitives (exact APIs — version-locked)

**Verified 1.21.1 signature** (markers: HAS `DeltaTracker`, NO `GpuBufferSlice`/
`GraphicsResourceAllocator`):

```
LevelRenderer.renderLevel(DeltaTracker partialTick, boolean renderBlockOutline, Camera camera,
    GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix /*view*/, Matrix4f projectionMatrix)
```

- **Off-screen target:** `new com.mojang.blaze3d.pipeline.TextureTarget(int w, int h, boolean
  useDepth, boolean checkError)` (extends `RenderTarget`). Color attachment is `GL_RGBA8` (no
  HDR). Render-thread methods: `bindWrite(boolean setViewport)`, `unbindWrite()`, `bindRead()`,
  `getColorTextureId()` (the sampleable GL id), `clear(checkError)`, `resize(...)`,
  `copyDepthFrom(...)`, `blitToScreen(...)`. Must be `destroyBuffers()`/closed on BE unload + on
  resource reload, or you leak FBOs and crash the GL context.
- **Camera:** `net.minecraft.client.Camera.setup(BlockGetter, Entity anchor, boolean detached,
  boolean thirdPersonReverse, float partialTick)` — **requires an Entity** and lerps from it. For
  an arbitrary fixed POV: place a hidden anchor entity, OR AT/mixin the protected
  `setPosition(Vec3)` + `setRotation(yaw,pitch)` (Nodewire already maintains a mixin package).
- **Pixels → GPU, zero-copy (Model A):** the FBO color attachment is already a GPU texture; wrap
  it in a thin `AbstractTexture` whose `getId()` returns `target.getColorTextureId()`, register
  once via `Minecraft.getInstance().getTextureManager().register(ResourceLocation, tex)`, and
  reference that `ResourceLocation` in a `RenderType`.
- **Pixels → GPU via DynamicTexture (Model B):** `net.minecraft.client.renderer.texture.
  DynamicTexture(int w, int h, boolean useAlpha)`; `getPixels(): NativeImage`;
  `NativeImage.setPixelRGBA(x,y, abgrInt)` (packed `0xAABBGGRR` ABGR); `upload()`; `getId()`.
  Capture FBO→CPU only if you must: `target.bindRead(); img.downloadTexture(0,false)`.
- **Draw on a block face:** implement `net.minecraft.client.renderer.blockentity.
  BlockEntityRenderer<T>#render(T, float partialTick, PoseStack, MultiBufferSource, int
  packedLight, int packedOverlay)`. Register on the **MOD bus, client**:
  `EntityRenderersEvent.RegisterRenderers#registerBlockEntityRenderer(BE_TYPE.get(), MyBER::new)`.
  Override `shouldRenderOffScreen(T) -> true` (render even when the block is frustum-culled) and
  bump `getViewDistance()`. RenderType by `ResourceLocation`: easiest is
  `RenderType.text(rl)` / `entityCutoutNoCull(rl)` / `entitySolid(rl)`; full control via
  `RenderType.create(name, fmt, mode, bufSize, CompositeState.builder().setTextureState(new
  RenderStateShard.TextureStateShard(rl,blur,mipmap))...createCompositeState(false))`.
  **1.21.1 still uses the OLD immediate `vertex().color().uv()...endVertex()` API.**
- **NeoForge hook:** `RenderLevelStageEvent` (main bus, client; `getPartialTick()` returns
  `DeltaTracker`). **Do NOT call `renderLevel` from inside it** — `renderLevel` calls
  `blockEntityRenderDispatcher.prepare(...)` + `entityRenderDispatcher.prepare(...)` at its top;
  a nested call stomps the global dispatcher camera. Schedule capture in a **pre-frame pump**
  instead (Nodewire already has `NodewireClient` on `ClientTickEvent.Post`).

**Severe version drift (a porting risk to record now):** 1.21.2 changed the immediate vertex API
(`Tesselator.begin()` → `BufferBuilder`, `addVertex(...)`, no `endVertex`, `MeshData`); 1.21.5
rewired `renderLevel` to take `GraphicsResourceAllocator` + `GpuBufferSlice` and replaced the
immediate BER `render(...)` with `createRenderState`/`extractRenderState`/`submit(SubmitNodeCollector,
CameraRenderState)`. **Everything above is 1.21.1 only.**

Shaders matter: Vista has explicit `CompatHandler.SODIUM` bailouts and `IrisCompat` nudges (Iris
tracks `RenderTarget` identity by a version counter that doesn't bump on a fresh target);
SecurityCraft disables the Fabulous extra targets entirely because "Fabulous breaks frame
capturing in too many ways." Capturing the world view is **not** shader-pack-neutral. FoundryMC/
Veil exists to abstract this fragility and is worth considering if cross-shader robustness matters.

---

## 4. DESIGN OPTIONS for a Nodewire "video" data type + dynamic-screen block

Anchor on the existing model. `PinValue.CODEC` is `Codec.STRING.dispatch("type", typeKey,
codecFor)` over a sealed class — **adding a new variant is mechanical** (new data class, new
per-variant `MapCodec`, two map entries in `typeKey`/`codecFor`, one branch in `default(type)`,
one enum constant in `PinType`). `LogicBlockEntity` already has the BE-sync hooks (§3.4). And
Nodewire already routes opaque tagged handles through the graph today: `EndpointRef =
(backendId: ResourceLocation, payload: EndpointPayload)` with an `UnknownPayload` fallback that
preserves raw NBT for forward-compat (`endpoint/EndpointRef.kt`). **A video handle is the same
shape of idea as an `EndpointRef`.**

### Option (a) — VIDEO/CAMERA HANDLE PinValue (server identity, client-side pixels)

A new `PinValue.VideoHandle` carrying a **tiny opaque source identity** — concretely a `UUID`
(Vista's exact choice) or a registered `Long`/`Int` stream id, optionally plus a small param
block (an enabled `Bool`, a `generation` counter, maybe target resolution). The graph routes
*which source feeds which screen* + scalar control (on/off, zoom, channel) — all already trivial
as `Bool`/`Float`. The heavy `RenderTarget`/`DynamicTexture` lives client-side in a registry
keyed by the handle, exactly like Vista's `Handle(uuid, screenSize) -> "live_feed/<uuid>_WxH"`.

The block has two flavors that share this handle type:
- **Camera source** = render the live world (Model A, §3.2). Server stores only the camera's
  pose (a `BlockPos` + yaw/pitch/fov, all small NBT — and for a camera inside a **Sable
  sub-level** this is just the sub-level pose, resolved client-side via
  `ClientSubLevelAccess.renderPose()`, the same split `SableSubLevelBackend` already does for
  endpoints). The client pre-frame pump renders that camera into the feed's `TextureTarget`.
- **Procedural source** = the graph paints into a client-side texture (no world capture at all).

Many screens consuming one handle **share a single capture** (Vista `BroadcastManager`;
SecurityCraft `linkedFrames`).

- **Pros:** PinValue stays a ~16-byte scalar — fully respects constraint 1. Highest fidelity
  (true game view) is available. Reuses the EXACT pattern Nodewire already ships for handle
  routing (`EndpointRef`) and for client-side pose resolution (Sable). Routing, copy/paste,
  fan-out (1 camera → N screens) are natural in a node graph. Verified-real on Nodewire's exact
  platform (Vista @ 21.1.228).
- **Cons:** A live world feed is the most expensive option (~(N+1)× level-render cost) and the
  most fragile — full render-state capture/restore, per-feed occlusion graph, recursion guard,
  and real Sodium/Iris compat work. Needs the adaptive scheduler + reference-counting + distance
  gating from day one or it tanks FPS. Handle **lifecycle** is a real design problem: who mints
  the UUID (must be server-side so it survives save and routes deterministically), how it
  survives graph copy/paste and ship-boundary moves, and how the client feed registry is pruned
  when no screen references the handle (prune-on-zero-refs, like Vista's `evictAfterTicks` +
  `markReferenced`).

### Option (b) — small fixed low-res pixel BUFFER type (OC/Tom's-style, actually serializable)

A `PinValue` (or, better, a dedicated screen-sink node + BE field — see recommendation) holding
a **hard-capped indexed or ARGB buffer**: OC-style palette-indexed cells (1 byte/cell, 160×50
cap → ~16 KB) or Tom's-style `int[]` ARGB at a tiny cap (64×64 = 16 KB). Sync via the OC
**delta-command** channel (§2) for efficiency, or the simpler Tom's **dirty-flagged vanilla
BE-sync** (§3.4) for a v1. Client realizes it into a `DynamicTexture` and draws one
`entityTranslucent` quad.

- **Pros:** Genuinely fits through serialization — proven by both OC (160×50 indexed, delta) and
  Tom's (64×64 ARGB over vanilla BE-sync). No world-capture machinery, no Sodium/Iris fragility,
  ~0 client cost when unchanged. Great for graph-generated content: oscilloscope/plot, icon,
  status dashboard, a Create-display-like readout — which is plausibly what a *logic* mod's
  "screen" actually wants. Maps directly onto Nodewire's existing BE-sync and (optionally) the
  `CustomPacketPayload` + `STREAM_CODEC` channel from Phase 4. Indexed palette keeps payloads
  tiny and NBT-friendly.
- **Cons:** Low fidelity by construction (palette banding for OC depth; a hard small resolution
  for Tom's). Putting an actual pixel buffer **into a per-tick `PinValue`** still violates
  constraint 1 if done naively — it must be a *node/BE that the graph drives by small commands*,
  synced **on change, never every tick** (Tom's syncs only on explicit GPU flush). For anything
  above trivial size you want OC's diff protocol (`setRegion`/`fillRect`/`blitHandle`), and that
  requires bit-identical mutators on both sides + periodic full-snapshot reconciliation. No live
  game-view.

### Option (c) — client-side TEXTURE-id reference

A `PinValue` carrying a `ResourceLocation` (or an int GL texture id) naming a client-side
texture, à la WebDisplays sampling `glBindTexture(GL_TEXTURE_2D, browser.getTextureID())`. The
producer (a browser, a video decoder, an external process) owns the texture; the screen just
samples it.

- **Pros:** Thinnest possible wire payload (a registry id). Decouples the screen from *how*
  pixels are produced — could front a CEF browser, a video file, or any client-side renderer.
- **Cons:** A bare GL int texture id is **not server-authoritative and not meaningful across
  clients** — it is a per-client, per-session handle. A `ResourceLocation` is shareable but only
  identifies a *registered* texture, so it still needs a server-minted logical identity to route
  through the graph (collapsing back into option (a)'s UUID handle). It pushes the
  pixel-production problem entirely out of the mod (WebDisplays requires the MCEF native lib;
  closed/heavyweight). On its own it does not answer "what is the source" — it is really a
  *client-side realization detail of* (a), not a standalone graph data type. Pure version of this
  (raw texture id in the PinValue) is the **wrong** choice; the useful 90% of it is already
  inside option (a).

### Summary table

| | (a) Video/Camera HANDLE | (b) Low-res BUFFER | (c) Texture-id ref |
|---|---|---|---|
| PinValue size | ~16 B (UUID) | small handle drives a capped BE buffer (~16 KB), NOT in PinValue | a `ResourceLocation`/int |
| Respects "no frames per tick" | yes | yes, IF buffer is BE-side + dirty-synced (never per-tick PinValue) | yes |
| Server-authoritative identity | yes (server mints UUID) | yes (BE owns buffer) | **no** (per-client/session) on its own |
| Fidelity | high (true world view) or procedural | low (palette / tiny res) | depends on producer |
| Client cost | high for live feed; needs adaptive throttle | ~0 when unchanged | depends on producer |
| Shader-pack fragility | high (world capture) | none | n/a (external) |
| Verified real on 1.21.1 | **Vista @ 21.1.228** | **Tom's Peripherals (NeoForge 1.21.x)** | WebDisplays (Forge 1.12 only) |
| Reuses existing Nodewire plumbing | `EndpointRef`-style handle + Sable pose split | `getUpdateTag`/BE-sync (+ optional Phase-4 packet channel) | — |

---

## 5. RECOMMENDATION

**Adopt a hybrid: a single `PinValue.VideoHandle` (option a) as the graph-level "video" type,
backed by two interchangeable client-side realizations — option (b) for graph-generated /
low-res content first, option (a)-live for game-view capture as a later, separately-tiered,
separately-budgeted feature. Reject option (c) as a standalone type (fold its useful part into
(a)).**

Rationale:

1. **The handle is the only thing that can travel the graph, and Nodewire already has the
   pattern.** Both real 1.21.1 reference mods that route a feed (Vista, SecurityCraft) carry a
   tiny identity (UUID / GlobalPos), never pixels. Nodewire's `EndpointRef` is the same shape and
   `PinValue.CODEC`'s dispatch makes adding a `VideoHandle` variant mechanical. The handle must be
   **server-minted** (so it survives world save, routes deterministically, and resolves across
   Sable sub-level moves) — mirror `EndpointRef`'s `UnknownPayload` forward-compat fallback so a
   handle to a removed/unloaded source degrades silently instead of corrupting the graph.

2. **Ship the cheap, robust client realization first (option b).** The first dynamic-screen block
   should be a **graph-driven low-res buffer** rendered with `DynamicTexture` →
   `RenderType.entityTranslucent` (Tom's pattern, §3.4) — it slots straight onto Nodewire's
   existing `getUpdateTag`/`ClientboundBlockEntityDataPacket`/`sendBlockUpdated` plumbing, has ~0
   cost when unchanged, no shader-pack fragility, and is almost certainly what a *logic* mod's
   screen is actually for (plots, gauges, status, icons). Hard-cap it (start 64×64; consider OC's
   indexed palette to shrink payloads) and **sync on change, never per tick.** For larger or
   busier screens, evolve toward OC's per-tick batched **delta-command** channel (§2) over a
   `CustomPacketPayload`, with a full-snapshot resync (`getUpdateTag` + a client re-request loop)
   for late/relocated viewers — Sable sub-level relocation makes that resync mandatory.

3. **Treat live game-view capture (option a-live) as an opt-in later tier.** It is the
   highest-fidelity and by far the most expensive/fragile path: per-feed `TextureTarget`,
   full render-state capture/restore, per-feed occlusion graph, recursion guard, and Sodium/Iris
   compat. If built, copy Vista's `AdaptiveUpdateScheduler` (`targetBudgetMs ~10% frame`,
   `guardTargetFps(60)`, `evictAfterTicks`, `markReferenced` reference-counting) and
   SecurityCraft's round-robin multiplexing + `shouldRenderOffScreen` + distance/back-face gating
   verbatim, run capture in the `NodewireClient` pre-frame pump (NOT inside `RenderLevelStageEvent`),
   and strongly consider depending on **FoundryMC/Veil** for cross-shader framebuffer robustness.
   Fan-out is free: many screens sharing one `VideoHandle` share one capture (Vista
   `BroadcastManager` / SecurityCraft `linkedFrames`); prune the client feed registry when refs
   hit zero.

4. **Reject pure option (c).** A raw GL texture id is per-client and not server-authoritative; a
   `ResourceLocation` still needs a server-minted logical identity to route — i.e. it collapses
   into (a). Its only genuinely-distinct value (an external producer like CEF) is a heavyweight,
   out-of-scope dependency.

Net: the "video" PinValue is a **server-minted handle**; the screen block resolves it
**client-side** to a `DynamicTexture` (graph-painted, v1) or a `TextureTarget` (live capture,
later). This preserves the server-eval / client-render split and the "no frames through a
PinValue" rule exactly, while leaving a clean upgrade path from a humble gauge to a CCTV monitor.

---

## 6. Open questions / risks

**Model / data type**
- Canonical `VideoHandle` schema: `UUID` vs registered `Long`; does it carry a `generation`
  counter and/or target resolution; does the small buffer of option (b) ever need to be
  graph-visible (almost certainly **never** — keep it BE-side, client-realized by handle)?
- Handle lifecycle: confirm a server-minted handle survives graph copy/paste, node deletion
  (no leak in the client feed registry), and Sable sub-level relocation; reuse `EndpointRef`'s
  `UnknownPayload` forward-compat fallback for dangling handles.

**Performance caps / max active cameras**
- For option (b): exact resolution + colour-depth cap that keeps a worst-case full-frame
  compressed packet to a few KB at the intended sync rate (Tom's proves 64×64 ARGB over vanilla
  BE-sync is fine on flush; OC proves 160×50 indexed + deltas).
- For option (a)-live: empirical ceiling on simultaneous feeds before frame time is unacceptable
  — **needs measurement (user runs `runClient`; do not launch it from here)**. Vista/SecurityCraft
  amortize rather than hard-cap (adaptive scheduler / round-robin), so the real knob is per-frame
  budget + viewer reference-counting + distance gating, not a fixed N.

**Multiplayer sync**
- Command-replay (OC §2) requires **bit-identical** mutators on both sides; plan periodic
  full-snapshot reconciliation. Keep one screen's per-tick ops in ONE packet (ordering/atomicity:
  OC terminates replay on `EOFException`); if ops ever exceed one packet, add explicit sequence +
  generation numbers.
- Distance/chunk-scope the screen channel (OC `sendToPlayersNearHost(range²)`) and gzip it, or
  many screens × many players multiplies bandwidth.

**Dimension / ship / sub-level**
- A camera bound inside a **Sable sub-level** needs that sub-level's client render pose
  (`ClientSubLevelAccess.renderPose()`) and may need to render the sub-level's `BlockGetter`, not
  the parent `Level` — spike against `SableSubLevelBackend`. Moving a screen across a sub-level
  boundary likely invalidates the client mirror/feed and forces a fresh snapshot; confirm the
  handle's logical identity survives the move (it must, since it is server-minted, not pose-derived).

**Rendering / API**
- 1.21.1 is version-locked (§3.5): porting past it forces rewrites (1.21.2 vertex API; 1.21.5
  `renderLevel`/BER `submit`). Confirm the exact 1.21.1 `VertexConsumer` chain against the
  parchment mappings ModDevGradle uses (check a built sources jar).
- Nodewire's Compose layer deliberately avoids `compose.ui`/Skiko; the in-world screen face needs
  a **separate vanilla `BlockEntityRenderer`** owning the `DynamicTexture`/`TextureTarget`
  (`NwCanvas`/`PaintWalk` are for the editor `Screen`, not block faces).
- FBO/texture lifecycle: `TextureTarget.destroyBuffers()` / `DynamicTexture.close()` on BE unload
  and on resource reload, and re-register on resize, or leak GL resources / crash the context.
- Sodium/Iris/Fabulous compatibility for any live-capture path is real, ongoing work (Vista
  `CompatHandler.SODIUM`/`IrisCompat`; SecurityCraft disables Fabulous targets). Veil is the
  recommended abstraction layer if it matters.
