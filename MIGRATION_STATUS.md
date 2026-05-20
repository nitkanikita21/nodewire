# NeoForge 1.21.1 Port — Status

Branch: `port/neoforge-1.21.1`. **Compiles and tests pass**; in-client smoke testing in progress. The `master` branch remains the working 1.20.1 Forge build.

## What's done

- [x] **Phase 1 — Toolchain swap.** `build.gradle.kts` rewritten for ModDevGradle non-legacy + NeoForge 21.1.230. `gradle.properties` updated. `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` with NeoForge schema. Java toolchain bumped 17 → 21. Yoga jar in `libs/` stays on Java 17 bytecode — runs on JDK 21 unchanged.
- [x] **Phase 2 — Research.** Confirmed maven coords for Create / Ponder / Flywheel / Registrate / JEI / EMI on 1.21.1. KFF NeoForge 5.5.0 picked. NeoForge 21.1.230 is the chosen stable.
- [x] **Phase 3 — `ResourceLocation` API.** 47 call sites migrated across 23 files.
- [x] **Phase 4 — Network rewrite.** All packets rewritten as `CustomPacketPayload` data classes with static `TYPE` + `STREAM_CODEC`. `NodewireNetwork` is now an `@EventBusSubscriber` on the mod bus that listens for `RegisterPayloadHandlersEvent`. Send sites switched to `PacketDistributor.sendToServer` / `sendToPlayer`. `HighlightPacket` (server→client) drops `DistExecutor`. Includes the new `BindAeroSourcePacket` from the Aeronautics integration.
- [x] **Phase 5 — Strip Valkyrien Skies 2.** `integration/vs/` removed. VS tests removed. Replaced by Sable.
- [x] **Phase 6 — Sable sub-level backend.** Built on **Sable Companion** (`dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`), a companion lib with safe no-op defaults that Sable replaces via Gradle capability resolution when installed — so we compile/run against Companion unconditionally without any `ModList.isLoaded` gate. Sable 1.2.2+ pinned as `runtimeOnly`. `integration/sable/SableSubLevelBackend.kt` implements `EndpointBackend` with payload `(UUID subLevelId, BlockPos)`. Sub-levels live as plot regions inside the parent `Level`, so `resolveBlockEntity` resolves the stored BlockPos through the parent level directly; `worldCenter`/`worldDirection` apply `SubLevelAccess.logicalPose()` server-side and `ClientSubLevelAccess.renderPose()` client-side (smooth partial-tick); `claims(level, worldPos)` consults `SableCompanion.INSTANCE.getContaining`. Registered in `Nodewire.init` BEFORE `WorldBackend` so the world remains the final fallback. Round-trip codec test: `SableSubLevelBackendCodecTest`.
- [x] **Phase 7 — Create Aeronautics on classpath.** `maven.modrinth:create-aeronautics:1.2.1+mc1.21.1` on `compileOnly + runtimeOnly`. Curse Maven 403's the project (monetized status on CurseForge), so we pulled from Modrinth instead. Aircraft built by Aeronautics ARE Sable sub-levels — claimed transparently by `SableSubLevelBackend`, so no extra integration code is needed for wires-on-aircraft.
- [x] **Phase 7-Aero — Aeronautics state integration.** Full per-block state binding for the 7 Aero block kinds (Smart/Andesite/Wooden Propeller, Hot-Air Burner, Steam Vent, Mounted Potato Cannon, Propeller Bearing) across ~33 channels. New: `AeroBlockKind`, `AeroChannel`, `AeroStatePipeline` (per-server-tick snapshot, ModList-gated), `AeroInputNode` (single typed-pin NodeType with channel-driven pin reshape), `NodeConfigContent.AeroInput` (compose UI), `AeroChannelPickerScreen`/`AeroTargetPickerScreen`, `BindAeroSourcePacket`, sneak+RMB Aero branch in `ChannelLinkToolItem`. Aero classes are reflection-accessed (jarjar wrapper has no top-level classes). Tests: `AeroChannelCatalogTest`, `AeroChannelReadTest`, `AeroInputCodecRoundtripTest`, `AeroStatePipelineGuardTest`.
- [x] **Phase 7-TC — Tweaked Controllers reinstated.** Initial strip was premature: TC 1.2.7 has a 1.21.1 NeoForge build (Curse file id 7958165). Files restored from `fa8738c~1` and ported to NeoForge API: `net.minecraftforge.*` → `net.neoforged.*`, `stack.tag` → `DataComponents.CUSTOM_DATA`. Mixins kept (`@Pseudo` + `remap = false`, package targets unchanged between 1.2.6 and 1.2.7).
- [x] **Phase 8 — API surface fixes.** All production files migrated to NeoForge:
  - `Registry.kt`: `DeferredRegister.Blocks/Items`, `DeferredBlock`/`DeferredItem`/`DeferredHolder`, `ofFullCopy`.
  - `LogicBlock.kt`: `use` → `useWithoutItem`. `DistExecutor` → `FMLEnvironment.dist`.
  - `LogicBlockEntity.kt`: `save/loadAdditional(HolderLookup.Provider, CompoundTag)`. `getUpdateTag` with `Provider`. `onDataPacket` removed (vanilla calls `loadCustomOnly`). Added `replaceNodeConfig` server-side hook used by `BindAeroSourcePacket`. Aero snapshot publish in `serverTick`.
  - `NodewireClient.kt`: `TickEvent.ClientTickEvent` → `ClientTickEvent.Post`.
  - Renderers + commands: package paths updated to `net.neoforged.neoforge.*`.
  - `NwComposeScreen.kt`: `mouseScrolled` gained 4th `scrollX` parameter; `renderBackground` signature updated.
  - `PinValue.kt`: `Codec.STRING.dispatch` → `MapCodec` form using `RecordCodecBuilder.mapCodec`.
  - `ChannelLinkToolItem.kt` + `PinValue.kt` constant-of-ItemStack: NBT → `DataComponents.CUSTOM_DATA` (`CustomData` wrapper).
  - `CreateRedstoneLink.kt` + `RedstoneLinkSlotPicker.kt`: `ItemStack.of` → registry-aware `ItemStack.parse(RegistryAccess, Tag)`. `frequencyOf(Level, ...)` for the new Create 6.0.10 signature.
- [x] **Phase 9 — Tests green.** `./gradlew test` passes: **338 tests, 0 failures** as of the Phase 9 commit; later test additions for the Aeronautics integration push the count higher (catalog + read + codec roundtrip + pipeline guard).
- [x] **Phase 10 — JPMS run-time fix.** NeoForge's multi-module layer made `kotlin.stdlib` not read `kotlinx.coroutines.core`; `launch{}` from the mod threw `IllegalAccessError`. Initial attempt `--add-opens=java.base/java.lang=nodewire` was silently ignored (module not in the boot layer at JVM start). Fix: `JpmsBridge.kt` + `JpmsBridgeHelper.java` — the helper class is loaded into an *anonymous* ClassLoader's UNNAMED module (where `--add-opens=java.base/java.lang=ALL-UNNAMED` actually applies), then calls `Module.implAddReads(kotlin.stdlib, kotlinx.coroutines.core)` via reflection. `--add-reads=kotlin.stdlib=kotlinx.coroutines.core` is also passed as a belt-and-braces.
- [x] **Editor — rename overlays.** Inline rename for nodes (`NodeLabelOverlay`) and groups (`GroupLabelOverlay`). `TextInput` gained `transparent` + `autoFocus`. Double-LMB on header or context menu → enter rename. Escape cancels rename → close menu → clear selection. Cross-resets between `renamingNode` and `renamingGroup`. Spec: `docs/superpowers/specs/2026-05-20-rename-overlays-design.md`.
- [x] **Editor — unified selection model.** `EditorState` keeps three selection sets (nodes / groups / comments). All three element renderers share identical press / drag / marquee / Del / accent-border logic. Marquee AABB-tests all three kinds (group bbox uses the same `GroupBbox` + `memberClosure` recipe as `GroupFrame`). `moveSelected` moves the union (directly-selected nodes + group-member closure + comments) in one undoable step. Press on an already-selected item is a no-op so the drag that follows moves the whole selection. `Ctrl+C/X/D` flatten selected groups into their member-node closure.
- [x] **Create deps fix-up.** `dependencySubstitution` for Create 6.0.10-280's typo'd Architectury POM coord (`13d.0.8` → `13.0.8`). `isTransitive = false` on Create slim variant to skip POM-declared optional deps (CC:Tweaked etc.). Added `maven.architectury.dev` repo.

## What's pending

- [ ] **In-game smoke test.** `./gradlew runClient` — confirm rename overlays / Aero binding / group operations all work as designed. Unit tests cover the data layer only.

## Versions targeted

| Component | Version |
| --- | --- |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.230` |
| Kotlin | `2.0.20` |
| Java toolchain | `21` |
| KFF (kotlinforforge-neoforge) | `5.5.0` |
| Create | `6.0.10-280` |
| Ponder | `1.0.82+mc1.21.1` |
| Flywheel | `1.0.6` |
| Registrate | `MC1.21-1.3.0+67` |
| Sable Companion | `sable-companion-common-1.21.1:1.6.0` (replaces VS2; safe defaults without Sable) |
| Sable | `1.2.2+mc1.21.1` (runtimeOnly) |
| Create Aeronautics | `1.2.1+mc1.21.1` (Modrinth maven — Curse Maven 403's monetized projects) |
| Tweaked Controllers | `1.2.7` (Curse file id 7958165) |
| MixinExtras | `0.4.1` |
| JEI | `19.21.0.247` |
| EMI | `1.1.18+1.21.1` |

## Why a separate branch

The port touches ~75% of files (every `ResourceLocation` use, every registry use, network layer, mods.toml schema, item config storage). `master` keeps the working 1.20.1 build for the v0.1.x user base; this branch will become master once in-client smoke testing passes.

When the branch is ready: PR → merge → bump `mod_version` → tag `v0.2.0` (NeoForge release). `dev-v*` tags trigger pre-release dev builds via `.github/workflows/release.yml`.
