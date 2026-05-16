# Create: Interactive Support — Design Spec

**Sub-project #2 of the cross-frame bindings effort.** Adds support for Create contraptions by depending on the [Create: Interactive](https://modrinth.com/mod/interactive) mod, which rewrites Create contraptions on top of VS2 ships. Foundation built in sub-project #1 (`VsShipBackend`) automatically covers this case — no new code required for the verify-only scope.

## Goal

Allow LogicBlocks placed on **Create contraptions** to participate in channel bindings (world↔contraption, contraption↔contraption, contraption↔ship), without writing any new backend.

## Architecture — why no code change

Create: Interactive replaces vanilla Create's contraption mechanism wholesale. From the mod description:

> *"A rewrite of Create's contraptions using VS2 to allow for building on existing contraptions, stacked contraptions, train derailment and more!"*

Under CI:

1. Player assembles a Create structure → CI calls VS API to create a new **ship**.
2. Structure blocks move from the world into the ship's shipyard region (standard VS mechanism).
3. The contraption's per-frame transform = the ship's transform.
4. `level.getBlockEntity(localPos)` on a contraption block returns the real BE (via VS hooks).

This means every contraption block, from Nodewire's perspective, looks identical to any other ship block:

- `level.getLoadedShipManagingPos(pos)` returns the CI-created ship.
- `EndpointRef.from(level, pos)` resolves to `VsShipPayload(shipId, localPos)`.
- `worldCenter()` returns `ship.renderTransform.toWorld.transformPosition(localCenter)`.

`VsShipBackend` already handles all of this. No `create_contraption` backend is needed.

## Deliverable

**Build change only:**

```kotlin
// build.gradle.kts
modRuntimeOnly("maven.modrinth:interactive:1.2.1")  // exact coord TBD per Modrinth maven response
```

Why `modRuntimeOnly`: we don't import any of CI's classes; we only need CI on the runtime classpath so it transforms contraptions into ships at runtime.

Existing deps already cover CI's hard requirements (Create 6.0.8, Valkyrien Skies 2.4.10, KFF 4.11.0).

## Manual verification (this is the "implementation")

After adding the dependency and `./gradlew build`:

1. `./gradlew runClient`.
2. Build a small Create contraption: place a Mechanical Bearing, attach a 2×2 block plate on top, place a LogicBlock on the plate, activate the bearing → contraption assembles and starts spinning.
3. Place a second LogicBlock on the ground nearby.
4. Use Channel Link Tool to bind world LogicBlock → contraption LogicBlock.
5. **Expected:** wire renders correctly, follows the spinning contraption, signal flows both directions.
6. Push contraption around with a Mechanical Piston / repeat with linear contraption.

If any step fails, escalate to a follow-up sub-project (assemble/disassemble lifecycle, separate vanilla-Create backend).

## Known scope gap (deferred)

**Assemble/disassemble lifecycle.** A binding created against a world block that is later assembled into a contraption becomes dangling (target is now on a ship at ship-local coords; original `EndpointRef.World(worldPos)` no longer resolves). Symmetrically, a binding to a contraption block that is later disassembled becomes dangling (ship is destroyed; `VsShipPayload(shipId, ...)` lookup returns null).

This is **out of scope** for verify-only. If manual verification (step 5 above) shows the static case works, this gap is the natural follow-up. A follow-up spec would add a listener on Create assemble/disassemble events (or VS `ShipLoadEvent` / `ShipUnloadEvent`) that rewrites affected `EndpointRef`s.

## Out of scope (separate sub-projects)

- Assemble/disassemble lifecycle migration (follow-up spec, only if needed).
- Vanilla Create contraptions without CI (entity-based, blocks in `Contraption.blocks` map — completely different code path).

## Decisions log

- **No new backend.** CI uses VS API; `VsShipBackend` already covers it.
- **`modRuntimeOnly`** not `modImplementation`. We never call CI's API directly.
- **Spec is intentionally short** — the work is one build.gradle.kts line + manual test. The architectural decision (no new code) is the entire substance.
