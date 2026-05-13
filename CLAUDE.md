# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Nodewire** — Minecraft Forge 1.20.1 Kotlin mod that replaces redstone with a node-based logic system. Designed to work across ship boundaries (Valkyrien Skies) and interoperate with Create. Uses a custom Jetpack Compose-based UI framework (no Skiko/AWT) for the in-world node editor.

## Stack

- **Minecraft** 1.20.1 + **Forge** 47.4.10 + **Kotlin** 2.0.20 + **KFF** 4.11.0 (KFF 4.12 requires Kotlin 2.2)
- **Build plugin:** **`net.neoforged.moddev.legacyforge` 2.0.141** (NOT ForgeGradle 6). Migrated 2026-05-13 because FG6 + non-MCP mappings + Mixin 0.8.5 fails to remap 3rd-party SRG refmaps (Ponder/EMI crash on load). ModDevGradle replaces all of that, plus uses `parchment` mappings via its own block.
- **Java toolchain:** 17 (Forge requirement). Yoga jar in `libs/` is rebuilt locally to Java 17 bytecode — upstream Maven release ships Java 21.
- **Compose runtime** 1.7.0 — `compose.runtime` ONLY (composition + recomposition). No `compose.ui`, no Skiko, no AWT. Both compose-runtime and yoga are **shaded into the mod jar** via `extractShadedLibs` task — declared `compileOnly` only. JPMS in ModDev isolates the mod's module from KFF's, so external libs with kotlin deps can't reach `kotlin.*` exports across module boundaries. Shading puts everything in our module's classes output, side-stepping the issue entirely.
- **Yoga** (AppliedEnergistics fork) — pure-Java flexbox for layout. Rebuild instructions in `docs/superpowers/notes/2026-05-13-yoga-rebuild.md`.
- **Integrations:** Valkyrien Skies 2, Create 6.0.8 + Ponder + Flywheel + Registrate, JEI, EMI — declared via `modImplementation`/`modCompileOnly`/`modRuntimeOnly` (ModDev auto-remaps SRG → Mojang).

## Commands

```bash
./gradlew build           # compile + reobf, ~30s incremental
./gradlew test            # JUnit 5 — Yoga/Compose smoke tests
./gradlew test --tests "<FQCN>"  # single test class
./gradlew dependencies    # debug classpath conflicts (Kotlin/coroutines/Compose)
```

ModDevGradle generates IDE runs automatically on Gradle sync; no `genIntellijRuns` task. Re-import the Gradle project in IntelliJ to refresh after build.gradle.kts changes.

**Do NOT run `./gradlew runClient` yourself** — the user runs it manually and reports results. `runServer`, `build`, `test`, `dependencies` are fine.

In-game testing: launch client, enter a world, press **N** to open the dev UI demo screen (`DemoScreen` bound via `NodewireClient`).

## Architecture

### Mod entrypoint
- `dev.nitka.nodewire.Nodewire` — `@Mod` class, common-side init.
- `dev.nitka.nodewire.Registry` — DeferredRegister setup for blocks/items/BEs.
- `dev.nitka.nodewire.client.NodewireClient` — `@Mod.EventBusSubscriber(value = [Dist.CLIENT])`, registers the `N` keybind via `RegisterKeyMappingsEvent` + listens to `TickEvent.ClientTickEvent` for `consumeClick()`.

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

3. **Render** — `render/NwCanvas.kt` wraps `GuiGraphics + Font` with an offset stack. `render/PaintWalk.kt` (`UiNode.renderWalk(canvas)`) walks the tree with `try/finally` around `popOffset`. `core/NwComposeScreen.kt` is the abstract `Screen` subclass — does NOT call `super.render()` (Compose owns layout). `isPauseScreen = false`.

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

### Implementation status
Phases 1–5 of the UI framework are complete (build setup → value types → applier/yoga → canvas → owner/screen). The framework can mount a real Compose composition into an MC `Screen`. Phase 6+ adds `Layout` primitive + `Box`/`Spacer`/`Row`/`Column`/`Text` composables, the theme system, input handling, and finally `Surface`/`Button`. The node editor itself is rebuilt on top of the framework after Phase 12.

Current plan: `docs/superpowers/plans/2026-05-13-compose-ui-framework.md`.

## Critical gotchas

- **`InputConstants`** — use `com.mojang.blaze3d.platform.InputConstants` (NOT `net.minecraft.client.util.InputConstants`, which doesn't exist in 1.20.1).
- **Yoga API quirks** — `setWidth(Float)` doesn't exist; use `StyleSizeLength.points(f)`. `removeChildAt`/`getChildAt` don't exist; use `removeChild(int)`/`getChild(int)`. To detach for re-attach use `removeChildAndInvalidate(YogaNode)`.
- **Event bus mismatch** — `@Mod.EventBusSubscriber` defaults to FORGE bus. `RegisterKeyMappingsEvent` fires on MOD bus, so a keymapping handler annotated alongside a tick handler in one Kotlin `object` silently never fires. Register MOD-bus handlers explicitly via KFF `MOD_BUS.addListener` from `Nodewire.init`.
- **legacyForge DSL** — inside `enable { ... }` block, use Groovy-style assignment `forgeVersion = "..."` (NOT `.set("...")`). Local `val forgeVersion: String by project` shadows the API — use a different name (we use `forgeVer`).
- **`google()` maven** is required — Compose runtime transitively pulls `androidx.annotation`.

## Docs layout

- `docs/superpowers/specs/` — design docs (one per slice, dated)
- `docs/superpowers/plans/` — implementation plans (one per spec, dated, phased)
- `docs/superpowers/notes/` — reference material (build recipes, API examples)

Plans use phase-based task lists with checkboxes; each phase ends in a commit. Spec → plan → execute (via `superpowers:subagent-driven-development` or inline) is the standard workflow.

## Communication

Respond in Ukrainian.
