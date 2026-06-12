# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Branch note.** `master` is the working **Forge 1.20.1** build (v0.1.x). The active branch `port/neoforge-1.21.1` is a **NeoForge 1.21.1** port (v0.2.0-dev). This document describes the port branch — for the 1.20.1 build, check out master.

## Project

**Nodewire** — Minecraft NeoForge 1.21.1 Kotlin mod that replaces redstone with a node-based logic system. Designed to work across ship boundaries (via **Sable** sub-levels) and interoperate with **Create** + **Create Aeronautics**. Uses a custom Jetpack Compose-based UI framework (no Skiko/AWT) for the in-world node editor.

## Stack

- **Minecraft** 1.21.1 + **NeoForge** 21.1.230 + **Kotlin** 2.0.20 + **KFF (kotlinforforge-neoforge)** 5.5.0.
- **Build plugin:** **`net.neoforged.moddev` 2.0.141** (non-legacy variant — no Forge-isms, no `legacyForge` block). ModDevGradle owns mappings (parchment), runs generation, and source-set wiring.
- **Java toolchain:** **21** (NeoForge requirement). Yoga jar in `libs/` is on Java 21 bytecode now.
- **Compose runtime** 1.7.0 — `compose.runtime` ONLY (composition + recomposition). No `compose.ui`, no Skiko, no AWT. compose-runtime + yoga are **shaded into the mod jar** via `extractShadedLibs` task — declared `compileOnly` only. JPMS in NeoForge isolates our module from KFF's; shading puts everything in our module's classes output.
- **Yoga** (AppliedEnergistics fork) — pure-Java flexbox for layout. Rebuild instructions in `docs/yoga-rebuild.md`.
- **Integrations:** **Sable Companion** 1.6.0 (no-op shim — Sable replaces it via Gradle capability resolution when installed), **Sable** 1.2.2+ (runtime), **Create** 6.0.10-280 + Ponder 1.0.82 + Flywheel 1.0.6 + Registrate 1.3.0+67, **Create Aeronautics** 1.2.1+ (Modrinth — Curse Maven 403s its monetized listing), **Tweaked Controllers** 1.2.7, JEI 19.21.x, EMI 1.1.18. All declared via `compileOnly`/`runtimeOnly` (ModDev auto-handles remapping).

## Commands

```bash
./gradlew build           # compile + reobf, ~30s incremental
./gradlew test            # JUnit 5 — Yoga/Compose smoke tests + codec roundtrips
./gradlew test --tests "<FQCN>"  # single test class
./gradlew dependencies    # debug classpath conflicts (Kotlin/coroutines/Compose)
```

ModDevGradle generates IDE runs automatically on Gradle sync; no `genIntellijRuns` task. Re-import the Gradle project in IntelliJ to refresh after build.gradle.kts changes.

**Do NOT run `./gradlew runClient` yourself** — the user runs it manually and reports results. `runServer`, `build`, `test`, `dependencies` are fine.

In-game testing: launch client, place a Nodewire block, right-click to open the editor.

## Architecture

### Mod entrypoint
- `dev.nitka.nodewire.Nodewire` — `@Mod` class, common-side init.
- `dev.nitka.nodewire.Registry` — DeferredRegister setup for blocks/items/BEs (NeoForge `DeferredRegister.Blocks/Items`, `DeferredBlock`/`DeferredItem`/`DeferredHolder`).
- `dev.nitka.nodewire.client.NodewireClient` — `@EventBusSubscriber(value = [Dist.CLIENT])`, listens to `ClientTickEvent.Post`.
- `dev.nitka.nodewire.net.NodewireNetwork` — `@EventBusSubscriber` on the MOD bus, registers `CustomPacketPayload` types in `RegisterPayloadHandlersEvent`. Send sites use `PacketDistributor.sendToServer` / `sendToPlayer`.

### UI framework (`dev.nitka.nodewire.ui`)
Custom Compose backend pinned to the MC client thread. Three-layer architecture:

1. **Composition** — real `androidx.compose.runtime` with a custom `AbstractApplier`:
   - `core/NwApplier.kt` — manages the `UiNode` tree. Uses `removeChildAndInvalidate(YogaNode)` (NOT `removeChild(int)`) because the int-overload doesn't clear `owner`, causing `Child already has a owner` on re-attach.
   - `core/NwClientDispatcher.kt` — `Dispatchers.Main.immediate`-style dispatcher: runs inline if `mc.isSameThread`, else `mc.execute(block)`.
   - `core/NwUiOwner.kt` — wires `Composition` + `Recomposer` + `BroadcastFrameClock` + `Snapshot.registerGlobalWriteObserver`. Single `applyScheduled` flag coalesces snapshot writes.

2. **Layout** — `Yoga` flexbox via `core/UiNode.kt`. `Modifier` chain is compiled in one `foldIn` pass per assignment, dispatching to three marker interfaces:
   - `LayoutModifierElement` → mutates the node's `YogaNode`
   - `StyleModifierElement` → collected for `Renderer`
   - `InputModifierElement` → collected for hit-testing
   - `core/YogaReset.kt` resets all touched Yoga properties between recompositions.

3. **Render** — `render/NwCanvas.kt` wraps `GuiGraphics + Font` with an offset stack. `render/PaintWalk.kt` walks the tree with `try/finally` around `popOffset`. `core/NwComposeScreen.kt` is the abstract `Screen` subclass — does NOT call `super.render()` (Compose owns layout). `isPauseScreen = false`. `mouseScrolled` has the NeoForge 4-arg signature (`scrollX`/`scrollY`).

### Modifier chain compilation
A `Modifier` is a linked list of `Element`s. `UiNode.modifier =` triggers a single `foldIn`:

```kotlin
value.foldIn(Unit) { _, element ->
    when (element) {
        is LayoutModifierElement<*> -> element.applyTo(yoga)
        is StyleModifierElement<*> -> styles.add(element)
        is InputModifierElement<*> -> inputs.add(element)
    }
}
```

This means new modifiers are added by implementing one of the three marker interfaces — never by switching on type elsewhere.

### Node editor — unified selection model
`EditorState` keeps **three** selection sets (`selectedNodes`, `selectedGroups`, `selectedComments`) — UUIDs of different element kinds would collide in a single set. All three element renderers (`NodeCard`, `GroupFrame` + `GroupCollapsedTile`, `CommentCard`) share the same press/drag/marquee logic:

- LMB on an *unselected* item → `clearSelection() + select(this)`.
- LMB on an *already selected* item → no-op (so drag that follows moves the whole selection).
- Shift+LMB → toggle.
- Marquee (drag on empty canvas) → AABB-tests all three kinds; Shift makes it additive.
- Drag on a selected item → `moveSelected(dx, dy)`, which moves directly-selected nodes + the closure of every selected group (recursive sub-groups too) + every selected comment, deduped, in one undoable step.
- `Del` deletes everything in the union.
- `Ctrl+C/X/D` flatten selected groups into their member-node closure (groups become a flat set of nodes on clipboard / on duplicate).
- Accent-border highlight (2 px) consistent across the three element types.

`GroupBbox` + `GroupProxyPins.memberClosure` are shared helpers — marquee hit-testing for groups uses the same recipe as `GroupFrame`'s bbox calculation.

### Unified pin linking (`link/`)
ONE surface for every cross-block relation, replacing the per-kind binding zoo (camera/touch/remote-redstone/aero/sensor packets are gone):

- `PinPort` — interface a BlockEntity implements to expose pins: `pinOutputs`/`pinInputs` (typed `LinkPin`s), `readPin -> PinReading?` (with optional `pulseStamp` for 1-tick event pins), `writePin`, `clearPin`. Implemented by `LogicBlockEntity` (channel nodes), `ScreenBlockEntity` (`touch`/`touch_down` out, `screen` in), `CameraBlockEntity` (`video` out, params in).
- `PinPorts` — resolver: BE-as-PinPort first, else a composite of adapters (Aero channel catalog, capability sensors, CBC cannon mount via `integration/cbc/CbcIntegration`, and the always-present redstone fallback `redstone` out / `redstone@<face>` in).
- `PinLink(source EndpointRef, sourcePin, targetPin)` — stored on the CONSUMING BE (`PinLinkSink`), persisted as `pin_links`, pulled per tick by `PinLinkEngine` (prune stale → read → write → clear silent pins). Legacy NBT (`camera_source_bindings` etc.) migrates on load.
- Net: `BindPinPacket` + `RemovePinLinkPacket` only. The redstone-face fallback still commits a `SideBinding` on the source logic block (push via `VirtualSignalMap`); legacy `ChannelBinding` push delivery remains for old saves but the tool no longer creates them.
- `ChannelLinkToolItem` modes (sneak+scroll): LINK (sneak+RMB = source pin picker, RMB = target pin picker) and PANEL (two-corner screen panel resize).

### Endpoint backends (`integration/<mod>/*Backend.kt`)
`EndpointBackend` resolves an `EndpointRef` (`(BackendId, payloadNbt)`) to a server-side `BlockEntity` + world-space pose. Order at registration in `Nodewire.init` matters — first registered wins on `claims(level, pos)`:

1. `SableSubLevelBackend` — payload `(UUID subLevelId, BlockPos)`. Resolves through the parent `Level` directly (sub-levels live as plot regions in the parent). `worldCenter`/`worldDirection` apply `SubLevelAccess.logicalPose()` server-side and `ClientSubLevelAccess.renderPose()` client-side for smooth partial-tick rendering. `claims` consults `SableCompanion.INSTANCE.getContaining`.
2. `WorldBackend` — fallback. Vanilla `Level.getBlockEntity(pos)` + identity pose.

### Aeronautics integration (`integration/aeronautics/`)
Aircraft built by Aeronautics ARE Sable sub-levels — the wire layer is transparent. On top of that, individual Aeronautics blocks expose state through `AeroChannel`s (`SMART_PROPELLER.rpm`, `HOT_AIR_BURNER.signal`, etc.). Pipeline:

- `AeroBlockKind` — enum of the 7 supported BE types, matches via reflection (`fqn` + hierarchy walk, ModList-guarded).
- `AeroChannel` — catalog (~33 channels, 7 kinds), each with a `read(be) -> PinValue` reflection accessor + a `writable: Boolean` flag.
- `AeroStatePipeline` — per-server-tick snapshot keyed by `AeroBindingKey(endpoint, kind, channel)`. ThreadLocal so node evaluators read in O(1). ModList-gated → no snapshot work when Aeronautics is absent.
- `AeroInputNode` — single typed-pin `NodeType` whose pin reshapes from `config.channel`. Evaluator looks up the snapshot.
- Bind UX: unified Link Tool — Aero blocks expose the channel catalog as output pins via the `PinPorts` adapter; links land on the consuming block as ordinary `PinLink`s (the old AeroChannelPicker/BindAeroSourcePacket flow is gone).

### CBC integration (`integration/cbc/` + script-facing `script/CbcBallistics.kt`)
- `Cbc` (script package, default-imported, in script-api.jar): live `ShellBallistics`/`PropellantBallistics` catalog + pure solver (`solvePitch`/`maxRange`) replaying CBC's exact integrator (`a = -min(drag·|v|·(|v| if quadratic), |v|)·v̂ + g·ŷ; pos += v + a/2; v += a`, verified against CBC 5.11 bytecode). Muzzle speed = Σ propellant `strength` + shell `addedChargePower`; dual-cannon (Military Supplement) shells carry fixed `initialVelocity`.
- `CbcIntegration` — reflective provider over CBC's runtime `MunitionPropertiesHandler` maps (data-driven, addon/datapack projectiles included; charge component found STRUCTURALLY by `addedChargePower()` accessor) + cannon-mount `PinPort` (`cannon_yaw`/`cannon_pitch` fields, `position`/`position_text` world-space via EndpointRef).

### Sub-skill workflow
Specs → plans → execution under `docs/superpowers/`:
- `docs/superpowers/specs/` — design docs (one per slice, dated)
- `docs/superpowers/plans/` — implementation plans (phase-based task lists with checkboxes)
- `docs/yoga-rebuild.md`, `docs/usage.md` — reference material at the docs root (not under `superpowers/`)

Plans are executed via `superpowers:subagent-driven-development` (preferred) or inline.

## Critical gotchas

- **`InputConstants`** — use `com.mojang.blaze3d.platform.InputConstants`.
- **Yoga API quirks** — `setWidth(Float)` doesn't exist; use `StyleSizeLength.points(f)`. `removeChildAt`/`getChildAt` don't exist; use `removeChild(int)`/`getChild(int)`. To detach for re-attach use `removeChildAndInvalidate(YogaNode)`.
- **JPMS module reads** — NeoForge runs in a multi-module layer; `kotlin.stdlib` doesn't read `kotlinx.coroutines.core` by default, so `launch{}` from a coroutine-using mod throws `IllegalAccessError`. Fixed via `JpmsBridge.kt` + `JpmsBridgeHelper.java`: helper class is loaded into an anonymous ClassLoader's *unnamed* module (where `--add-opens=java.base/java.lang=ALL-UNNAMED` actually applies), then calls `Module.implAddReads(kotlin.stdlib, kotlinx.coroutines.core)` via reflection. The run task also passes `--add-reads=kotlin.stdlib=kotlinx.coroutines.core` as a belt-and-braces.
- **`mods.toml` → `neoforge.mods.toml`** — schema is different. Use `[[mixins]]` blocks (not `mixins = [...]` array), drop Forge-only fields.
- **Network rewrite (Phase 4)** — packets are `CustomPacketPayload` data classes with static `TYPE` + `STREAM_CODEC`. Register in `RegisterPayloadHandlersEvent`, not the old `SimpleChannel` API. Server→client packets drop `DistExecutor`.
- **`ItemStack` NBT** — replaced by data components. `stack.tag` → `DataComponents.CUSTOM_DATA` (`CustomData` wrapper). `ItemStack.of(tag)` → `ItemStack.parse(RegistryAccess, Tag)`.
- **Block.use vs useWithoutItem** — vanilla split the methods. Use `useWithoutItem` when ignoring the held item.
- **BE save/load signatures** — `save(HolderLookup.Provider, CompoundTag)` and `loadAdditional(HolderLookup.Provider, CompoundTag)`. `getUpdateTag(Provider)`. `onDataPacket` removed — vanilla calls `loadCustomOnly`.
- **Aeronautics deps** — Modrinth maven only. The Curse Maven 403s on monetized listings. Aero classes are inside a jarjar wrapper (no top-level `.class` files) → integration uses qualified-name reflection, not direct compile-time references.
- **Compose ComposableSingletons stale cache** — incremental Kotlin/Compose compile occasionally drops a `ComposableSingleton$lambda-N` → `NoSuchMethodError` at runtime. Fix: `./gradlew clean :compileKotlin`.
- **PinValue vectors are DOUBLE** — `PinValue.Vec2/Vec3/Quat` and script `Vec2/Vec3/Quat` carry doubles (float quantizes far-lands coords by whole blocks). Float secondary constructors keep old call sites compiling; codecs are `Codec.DOUBLE` (legacy FloatTag NBT parses fine via NbtOps).
- **JOML in scripts = three-layer contract** — (1) K2 can't read the boot-layer MODULAR joml jar ("Cannot access class org.joml.*" in-game only), so `:scripting` bundles a module-info-stripped `joml.jar` for the COMPILE classpath; (2) that jar must stay OFF the Backend URLClassLoader URLs and `org.joml.` must be parent-delegated there — a child-first copy caused `LinkageError: loader constraint violation` that silently killed tick behaviors; (3) eval config sets `loadDependencies(false)` so every script symbol resolves through the sandbox. Single identity = MC's joml everywhere at runtime.
- **`ServerLevel.getBlockEntity` returns null OFF the server thread** (vanilla race guard) — cross-thread peeks (editor preview bridge) must hop via `server.execute {}` and publish through an atomic.
- **Vanilla ESC closes screens before your keyPressed branch** — `Screen.keyPressed` handles ESC → `onClose()` → your post-`super` ESC code is dead. Commit-on-close logic belongs in an `onClose()` override (ScriptEditorScreen lesson).
- **Evaluator skips were vacuous for outputless nodes** — `outputs.all { provided }` on an EMPTY pin list is true; side-effect-only nodes (a `chat()`-only script) never evaluated. Guard with `outputs.isNotEmpty() &&` (regression: `OutputlessNodeEvaluationTest`).
- **Editor preview externals** — channel deliveries are server-only; the preview evaluator gets a per-tick snapshot from the integrated server (`LogicBlockEntity.externalInputsSnapshot`, ConcurrentHashMap). On dedicated servers preview channel chips stay at defaults.

## Communication

Respond in Ukrainian.
