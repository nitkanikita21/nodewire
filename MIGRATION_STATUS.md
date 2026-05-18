# NeoForge 1.21.1 Port — Status

Branch: `port/neoforge-1.21.1`. **This branch does not compile yet** — porting is in progress phase-by-phase. The `master` branch remains the working 1.20.1 Forge build.

## What's done

- [x] **Phase 1 — Toolchain swap.** `build.gradle.kts` rewritten for ModDevGradle non-legacy + NeoForge 21.1.172. `gradle.properties` updated. `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` with NeoForge schema. Java toolchain bumped 17 → 21.

## What's pending

- [ ] **Phase 2 — Research breaking changes** (NeoForge migration primer, KFF 5.x quirks).
- [ ] **Phase 3 — `ResourceLocation` + Registry API.** Replace `ResourceLocation("ns", "path")` with `ResourceLocation.fromNamespaceAndPath("ns", "path")` everywhere (~33 sites). `Registries.*` → `BuiltInRegistries.*`.
- [ ] **Phase 4 — Network rewrite.** `SimpleChannel` was removed in 1.20.5+. `NodewireNetwork.kt` + `SaveGraphPacket.kt` must move to `PayloadRegistrar` + `CustomPacketPayload` records with explicit `StreamCodec`.
- [ ] **Phase 5 — Remove Valkyrien Skies 2 backend.** Strip `integration/vs/`, drop `EndpointBackend` for VS. Leave a stub so the `EndpointRef` type system still type-checks.
- [ ] **Phase 6 — Sable sub-level backend.** New `integration/sable/SableSubLevelBackend.kt` implementing `EndpointBackend`. Codec on `(subLevelId, local BlockPos)`. Lookup via Sable's sub-level API.
- [ ] **Phase 7 — Create + Create Aeronautics integration.** Bump Create code paths to 6.0.10. Add Aeronautics-specific nodes if the mod's API exposes signal hooks.
- [ ] **Phase 8 — Restore green tests.** 330+ tests, many touching codec/registry/network — expect partial green initially, work file-by-file.

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
