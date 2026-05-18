<p align="center">
  <img src="docs/logo.svg" alt="Nodewire logo" width="200"/>
</p>

<h1 align="center">Nodewire</h1>

<p align="center">
  A Minecraft Forge 1.20.1 mod that replaces redstone with a node-based logic system. Build logic visually in an in-world editor, then export it as a reusable graph — across ship boundaries (Valkyrien Skies) and alongside Create.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-early%20development-f59e0b?style=flat-square" alt="Status: early development"/>
  <img src="https://img.shields.io/badge/minecraft-1.20.1-62a834?style=flat-square&logo=minecraft&logoColor=white" alt="Minecraft 1.20.1"/>
  <img src="https://img.shields.io/badge/forge-47.4.10-1f2937?style=flat-square" alt="Forge 47.4.10"/>
  <img src="https://img.shields.io/badge/kotlin-2.0.20-7f52ff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.0.20"/>
  <img src="https://img.shields.io/github/license/nitkanikita21/nodewire?style=flat-square&color=86c61e" alt="License: MIT"/>
  <img src="https://img.shields.io/github/actions/workflow/status/nitkanikita21/nodewire/build.yml?branch=master&style=flat-square&label=build" alt="Build status"/>
</p>

> Core editor, save/load, groups, comments, and wire labels are working. APIs and save formats are not yet stable.

## Features

- **Visual node editor** opened on a placed Nodewire block. Pan, zoom, drag nodes, draw wires.
- **Save & load graphs** to client-side files (`<gamedir>/nodewire-graphs/<name>.snbt`) and reuse them between worlds.
- **Node groups** — collapse parts of a graph into a reusable subgraph. Groups can be nested, saved as standalone templates, and live-synced when you edit the master.
- **Comments + wire labels** — annotate the editor with floating text boxes and label individual wires for legibility.
- **Cross-ship logic** — designed to function across Valkyrien Skies ship boundaries.
- **Create-friendly** — works alongside Create 6.0.8 + Ponder + Flywheel.

## Stack

- Minecraft **1.20.1** + Forge **47.4.10**
- Kotlin **2.0.20** + Kotlin for Forge **4.11.0**
- Build plugin: **`net.neoforged.moddev.legacyforge` 2.0.141** (ModDevGradle, not ForgeGradle 6)
- Java toolchain: **17**
- Custom UI framework on top of **Compose runtime 1.7.0** (no Skiko, no AWT) + **Yoga** (AppliedEnergistics fork, rebuilt for Java 17)
- Integrations: Valkyrien Skies 2, Create 6.0.8, JEI, EMI

## Build

```bash
./gradlew build      # compile + reobf, ~30s incremental
./gradlew test       # unit tests — run locally; CI skips them (see note below)
```

> **CI note:** GitHub Actions runs `./gradlew build -x test` only. ModDevGradle's Forge-class remap (SRG → Mojang names) doesn't refresh the SHA-384 digests in `META-INF/MANIFEST.MF`, and JarVerifier on a fresh CI cache rejects test discovery. Locally this is a non-issue — the cache stays in a known-good state. Run `./gradlew test` before pushing.

ModDevGradle generates IDE run configurations automatically on Gradle sync — re-import the project in IntelliJ after editing `build.gradle.kts`. No manual `genIntellijRuns`.

The bundled `libs/yoga-1.0.0-j17.jar` is included so the project builds out of the box. To rebuild it yourself (e.g., when bumping the Yoga version) see [`docs/yoga-rebuild.md`](docs/yoga-rebuild.md).

## In-game

1. Launch the client (`./gradlew runClient` or via IntelliJ).
2. Enter a world.
3. Press **N** to open the dev demo screen, or place the Nodewire block and right-click it to open the editor.

See [`docs/usage.md`](docs/usage.md) for the editing workflow, keybinds, node categories, and mod-integration details.

## Project layout

```
src/main/kotlin/dev/nitka/nodewire/
├── Nodewire.kt              # @Mod entrypoint
├── Registry.kt              # DeferredRegister for blocks/items/BEs
├── client/
│   ├── NodewireClient.kt    # client init, keybinds
│   └── screen/              # node editor screens, layers, dialogs
├── graph/                   # graph model: Node, Edge, NodeGraph, Group, Comment, codecs
└── ui/                      # custom Compose runtime UI framework
    ├── core/                # Applier, dispatcher, owner, YogaNode wiring
    ├── components/          # Text, TextInput, TextArea, Button, Surface, etc.
    ├── modifier/            # Modifier elements (layout / style / input)
    └── render/              # NwCanvas + render walk
```

Architectural details (UI framework layering, modifier compilation, gotchas) live in [`CLAUDE.md`](CLAUDE.md).

## Contributing

This project is in early development. If you'd like to contribute:

1. Open an issue first to discuss the change.
2. Match existing patterns — look at neighbouring files before introducing new abstractions.
3. Add unit tests where reasonable; the existing test suite (under `src/test/kotlin`) covers graph codecs, group operations, and the proxy-pin labeling rules.

## License

[MIT](LICENSE) © 2026 nitka
