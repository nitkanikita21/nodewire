# NeoForge 1.21.1 Port — Status

Branch: `port/neoforge-1.21.1`. **This branch does not compile yet** — porting is in progress phase-by-phase. The `master` branch remains the working 1.20.1 Forge build.

## What's done

- [x] **Phase 1 — Toolchain swap.** `build.gradle.kts` rewritten for ModDevGradle non-legacy + NeoForge 21.1.172. `gradle.properties` updated. `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` with NeoForge schema. Java toolchain bumped 17 → 21. `./gradlew help` passes.
- [x] **Phase 2 — Research.** Confirmed maven coords for Create / Ponder / Flywheel / Registrate / JEI / EMI on 1.21.1. KFF NeoForge 5.5.0 picked for 1.21.1 (5.6+ moves to 1.21.3 baseline). NeoForge 21.1.172 is the chosen stable.
- [x] **Phase 3 — `ResourceLocation` API.** All 47 call sites migrated to `fromNamespaceAndPath` / `parse` across 23 files (8 prod + 15 test). Sed pass + 8 manual edits. No `ResourceLocation` errors remain in `compileKotlin`.

## In-progress / blocked

- **Sable + Create Aeronautics deps** — provisional maven coords didn't resolve on first attempt. Temporarily commented out in `build.gradle.kts` (TODOs marked). Re-enabled in Phase 6/7 once verified.

## What's pending — 32 files still failing compile

After Phase 3, `./gradlew compileKotlin` lists 32 broken Kotlin files. Buckets:

- [ ] **Phase 4 — Network rewrite (~9 files).** `SimpleChannel` / `NetworkEvent` / `Supplier<NetworkEvent.Context>` were removed in 1.20.5+. Files to rewrite as `CustomPacketPayload` records + `PayloadRegistrar`:
  - `NodewireNetwork.kt` (channel setup)
  - `SaveGraphPacket.kt`, `RemoveBindingPacket.kt`, `SetBlockNamePacket.kt`, `SetSideBindingNamePacket.kt`, `BindChannelPacket.kt`, `BindSideChannelPacket.kt`, `HighlightPacket.kt`
- [ ] **Phase 5 — Strip Valkyrien Skies 2.** Delete `integration/vs/VsShipBackend.kt` + remove its registration from `EndpointBackends`. Tests `EndpointBackendsTest.kt` / `EndpointRefCodecTest.kt` need VS payload references removed.
- [ ] **Phase 6 — Sable backend.** New file. Needs Sable API research (read their public Javadoc / examples). Verify maven coord, re-enable in `build.gradle.kts`.
- [ ] **Phase 7 — Create + Aeronautics integration.** Bump `CreateRedstoneLink.kt` to 6.0.10 APIs. Stub or remove Tweaked Controllers integration (mod has no 1.21.1 release that I found — `ControllerBindHandler.kt` / `ControllerHubItem.kt` / `TweakedController.kt` need decision: drop entirely or wait for upstream).
- [ ] **Phase 8 — API surface fixes (~15 files):**
  - **Item / DataComponent migration** — `ItemStack.getTag()` removed. `ChannelLinkToolItem.kt` stores binding in NBT — must move to DataComponent (`DeferredHolder<DataComponentType<?>, DataComponentType<MyData>>`).
  - **`Screen.mouseScrolled` signature** — added `double deltaX` parameter in 1.20.5+. Affects `NwComposeScreen.kt`.
  - **Block/Item registration** — `RegistryObject<T>` → `DeferredHolder<R, T>`. `Registry.kt` plus all consumers (`Registry.LOGIC_BLOCK.get()` style stays but the type changes).
  - **Event subscribers** — `@SubscribeEvent` is fine, but `RegisterCommandsEvent` / `RegisterClientCommandsEvent` paths differ. `HighlightCommand.kt`, `HighlightServerCommand.kt`.
  - **Renderer hooks** — `BlockHighlightRenderer.kt`, `WireWorldRenderer.kt` use `RenderLevelStageEvent` (still exists, but Forge → NeoForge package change).
  - Misc: `LogicBlock.kt`, `LogicBlockEntity.kt`, `BindingsManagerScreen.kt`, `EditorToolbar.kt`, `GraphExporter.kt`, `GraphFiles.kt`, `GroupFiles.kt`, `NodeEditorScreen.kt`, `RedstoneLinkSlotPicker.kt`, `PinValue.kt`.
- [ ] **Phase 9 — Restore green tests.** 330+ tests, many touch the same APIs above. Most should auto-fix once production code compiles; some will need explicit adaption (`EndpointBackendsTest`, `SaveGraphPacketTest`).

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
