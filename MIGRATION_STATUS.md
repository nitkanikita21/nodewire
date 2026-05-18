# NeoForge 1.21.1 Port — Status

Branch: `port/neoforge-1.21.1`. **This branch does not compile yet** — porting is in progress phase-by-phase. The `master` branch remains the working 1.20.1 Forge build.

## What's done

- [x] **Phase 1 — Toolchain swap.** `build.gradle.kts` rewritten for ModDevGradle non-legacy + NeoForge 21.1.172. `gradle.properties` updated. `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` with NeoForge schema. Java toolchain bumped 17 → 21.
- [x] **Phase 2 — Research.** Confirmed maven coords for Create / Ponder / Flywheel / Registrate / JEI / EMI on 1.21.1. KFF NeoForge 5.5.0 picked. NeoForge 21.1.172 is the chosen stable.
- [x] **Phase 3 — `ResourceLocation` API.** 47 call sites migrated across 23 files.
- [x] **Phase 5 — Strip Valkyrien Skies 2.** `integration/vs/` removed. `EndpointBackends` no longer registers VS. VS tests removed.
- [x] **Phase 7 (TC part) — Strip Tweaked Controllers.** No 1.21.1 release exists for the mod we used; entire `integration/tweakedcontroller/` package removed (7 files + 2 tests + 2 mixin classes). `controller_input` node-type registration replaced with `// TODO(post-port)`. Editor toolbar / EditorState / NodeConfigContent gates the controller UI behind the same TODO. ~1300 LOC removed.
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
- [x] **Phase 9 — Tests green.** `./gradlew test` passes: **320 tests, 0 failures.** (Was 337 on master; -17 = TC tests removed in Phase 5+7.) Only one assertion adjustment needed: `StockNodeTypesTest` expected count 24 → 23 (`controller_input` removed).
- [x] **Create deps fix-up.** `dependencySubstitution` for Create 6.0.10-280's typo'd Architectury POM coord (`13d.0.8` → `13.0.8`). `isTransitive = false` on Create slim variant to skip POM-declared optional deps (CC:Tweaked etc.). Added `maven.architectury.dev` repo.

## What's pending

- [ ] **Phase 6 — Sable sub-level backend.** Replaces VS2's role. Needs Sable API research (their wiki, javadocs, or source). Steps:
  1. Confirm exact maven coord against `https://maven.ryanhcode.dev/releases` (current guess: `dev.ryanhcode.sable:sable-neoforge:1.2.2+mc1.21.1`). The provisional dep is commented out in `build.gradle.kts`.
  2. Add `SableSubLevelBackend.kt` under `integration/sable/` implementing `EndpointBackend`. Payload: `(subLevelId, local BlockPos)`. Lookup: Sable's sub-level API.
  3. Register the backend in `EndpointBackends`.
  4. Add round-trip codec test (mirror of `EndpointRefCodecTest` for the new payload type).
- [ ] **Phase 7 (Aeronautics part) — Create Aeronautics integration.** Re-enable the Curse Maven dep (`curse.maven:create-aeronautics-676721:8003941`, currently commented out). Decide what nodes / hooks the mod exposes that Nodewire should integrate (signal sources on aircraft, etc.).
- [ ] **Post-port TODO sweep.** ~6 `// TODO(post-port)` markers left across `StockNodeTypes`, `EditorState`, `EditorToolbar`, `NodeConfigContent`, `LogicBlockEntity`. Each is a small concrete decision (drop or reimplement against new APIs). Grep with:
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
| Sable | `1.2.2+mc1.21.1` (replaces VS2) |
| Create Aeronautics | `1.2.1` (Curse Maven file `8003941`) |
| MixinExtras | `0.4.1` |
| JEI | `19.21.0.247` |
| EMI | `1.1.18+1.21.1` |

Some maven coordinates are best-guess from public docs — first `./gradlew dependencies` run on this branch will validate them, with corrections committed as fixes.

## Why a separate branch

The port touches ~75% of files (every `ResourceLocation` use, every registry use, network layer, mods.toml schema, item config storage). It will take days to bring tests back to green. `master` keeps the working 1.20.1 build for the v0.1.0 user base; this branch will become master only when it builds and tests pass.

When the branch is ready: PR → merge → bump `mod_version` → tag `v0.2.0` (NeoForge release).
