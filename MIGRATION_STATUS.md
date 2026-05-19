# NeoForge 1.21.1 Port — Status

Branch: `port/neoforge-1.21.1`. **This branch does not compile yet** — porting is in progress phase-by-phase. The `master` branch remains the working 1.20.1 Forge build.

## What's done

- [x] **Phase 1 — Toolchain swap.** `build.gradle.kts` rewritten for ModDevGradle non-legacy + NeoForge 21.1.172. `gradle.properties` updated. `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` with NeoForge schema. Java toolchain bumped 17 → 21.
- [x] **Phase 2 — Research.** Confirmed maven coords for Create / Ponder / Flywheel / Registrate / JEI / EMI on 1.21.1. KFF NeoForge 5.5.0 picked. NeoForge 21.1.172 is the chosen stable.
- [x] **Phase 3 — `ResourceLocation` API.** 47 call sites migrated across 23 files.
- [x] **Phase 5 — Strip Valkyrien Skies 2.** `integration/vs/` removed. `EndpointBackends` no longer registers VS. VS tests removed.
- [x] **Phase 7-TC — Tweaked Controllers reinstated.** Initial strip was premature: TC 1.2.7 has a 1.21.1 NeoForge build (Curse file id 7958165, project id 898849). Files restored from `fa8738c~1` and ported to NeoForge API: `net.minecraftforge.*` → `net.neoforged.*`, `stack.tag` → `DataComponents.CUSTOM_DATA`. Mixins kept (`@Pseudo` + `remap = false`, package targets unchanged between 1.2.6 and 1.2.7). All call sites in `Nodewire.kt`, `LogicBlockEntity.kt`, `EditorState.kt`, `EditorToolbar.kt`, `NodeConfigContent.kt`, `StockNodeTypes.kt`, `nodewire.mixins.json` restored.
- [x] **Phase 4 — Network rewrite.** All 7 packets rewritten as `CustomPacketPayload` data classes with static `TYPE` + `STREAM_CODEC`. `NodewireNetwork` is now an `@EventBusSubscriber` on the mod bus that listens for `RegisterPayloadHandlersEvent`. Send sites switched to `PacketDistributor.sendToServer` / `sendToPlayer`. `HighlightPacket` (server→client) drops `DistExecutor`.
- [x] **Phase 8 — API surface fixes.** All 15 remaining production files migrated to NeoForge:
  - `Registry.kt`: `DeferredRegister.Blocks/Items`, `DeferredBlock`/`DeferredItem`/`DeferredHolder`, `ofFullCopy`.
  - `LogicBlock.kt`: `use` → `useWithoutItem`. `DistExecutor` → `FMLEnvironment.dist`.
  - `LogicBlockEntity.kt`: `save/loadAdditional(HolderLookup.Provider, CompoundTag)`. `getUpdateTag` with `Provider`. `onDataPacket` removed (vanilla calls `loadCustomOnly`).
  - `NodewireClient.kt`: `TickEvent.ClientTickEvent` → `ClientTickEvent.Post`.
  - Renderers + commands: package paths updated to `net.neoforged.neoforge.*`.
  - `NwComposeScreen.kt`: `mouseScrolled` gained 4th `scrollX` parameter; `renderBackground` signature updated.
  - `PinValue.kt`: `Codec.STRING.dispatch` → `MapCodec` form using `RecordCodecBuilder.mapCodec`.
  - `ChannelLinkToolItem.kt` + `PinValue.kt` constant-of-ItemStack: NBT → `DataComponents.CUSTOM_DATA` (`CustomData` wrapper).
  - `CreateRedstoneLink.kt` + `RedstoneLinkSlotPicker.kt`: `ItemStack.of` → registry-aware `ItemStack.parse(RegistryAccess, Tag)`. `frequencyOf(Level, ...)` for the new Create 6.0.10 signature.
- [x] **Phase 9 — Tests green.** `./gradlew test` passes: **338 tests, 0 failures** (337 from parity-with-master after TC reinstate + 1 new `SableSubLevelBackendCodecTest`). `StockNodeTypesTest` count back to 24.
- [x] **Phase 7 — Create Aeronautics on classpath.** `maven.modrinth:create-aeronautics:1.2.1+mc1.21.1` on `compileOnly + runtimeOnly`. Curse Maven 403's the project (monetized status on CurseForge), so we pulled from Modrinth instead. Aircraft built by Aeronautics ARE Sable sub-levels — claimed transparently by `SableSubLevelBackend`, so no extra integration code is needed for wires-on-aircraft. Aeronautics-specific node types (signal sources on aircraft, propeller channels, etc.) can be added later when a concrete use case emerges.
- [x] **Phase 6 — Sable sub-level backend.** Built on **Sable Companion** (`dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`), a companion lib with safe no-op defaults that Sable replaces via Gradle capability resolution when installed — so we can compile/run against Companion unconditionally without any `ModList.isLoaded` gate. `integration/sable/SableSubLevelBackend.kt` implements `EndpointBackend` with payload `(UUID subLevelId, BlockPos)`. Sub-levels live as plot regions inside the parent `Level`, so `resolveBlockEntity` resolves the stored BlockPos through the parent level directly; `worldCenter`/`worldDirection` apply `SubLevelAccess.logicalPose()` server-side and `ClientSubLevelAccess.renderPose()` client-side (smooth partial-tick); `claims(level, worldPos)` consults `SableCompanion.INSTANCE.getContaining`. Registered in `Nodewire.init` BEFORE `WorldBackend` so the world remains the final fallback. Round-trip codec test: `SableSubLevelBackendCodecTest`.
- [x] **Create deps fix-up.** `dependencySubstitution` for Create 6.0.10-280's typo'd Architectury POM coord (`13d.0.8` → `13.0.8`). `isTransitive = false` on Create slim variant to skip POM-declared optional deps (CC:Tweaked etc.). Added `maven.architectury.dev` repo.

## What's pending

- [ ] **Post-port TODO sweep.** All `// TODO(post-port)` markers from Phase 5+7 strip were cleared in Phase 7-TC reinstate. Re-check with:
  ```
  grep -rn "TODO(post-port)" src/main/kotlin/
  ```
- [ ] **In-game smoke test.** `./gradlew runClient` — confirm the editor opens, packets fly, save/load works, Create integration still functions. None of this was tested in-game during the port; unit tests cover the data layer only.

## Versions targeted

| Component | Version |
| --- | --- |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.172` |
| Kotlin | `2.0.20` |
| Java toolchain | `21` |
| KFF (kotlinforforge-neoforge) | `5.5.0` (provisional) |
| Create | `6.0.10-280` |
| Ponder | `1.0.82` |
| Flywheel | `1.0.6` |
| Registrate | `MC1.21-1.3.0+67` |
| Sable Companion | `sable-companion-common-1.21.1:1.6.0` (replaces VS2; safe defaults without Sable) |
| Create Aeronautics | `1.2.1+mc1.21.1` (Modrinth maven — Curse Maven 403's monetized projects) |
| MixinExtras | `0.4.1` |
| JEI | `19.21.0.247` |
| EMI | `1.1.18+1.21.1` |

Some maven coordinates are best-guess from public docs — first `./gradlew dependencies` run on this branch will validate them, with corrections committed as fixes.

## Why a separate branch

The port touches ~75% of files (every `ResourceLocation` use, every registry use, network layer, mods.toml schema, item config storage). It will take days to bring tests back to green. `master` keeps the working 1.20.1 build for the v0.1.0 user base; this branch will become master only when it builds and tests pass.

When the branch is ready: PR → merge → bump `mod_version` → tag `v0.2.0` (NeoForge release).
