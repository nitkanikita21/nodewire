<p align="center">
  <img src="docs/logo.svg" alt="Nodewire logo" width="200"/>
</p>

<h1 align="center">Nodewire</h1>

<p align="center">
  A Minecraft NeoForge 1.21.1 mod that replaces redstone with a node-based logic system. Build logic visually in an in-world editor, then export it as a reusable graph — across sub-level boundaries (Sable) and alongside Create + Create Aeronautics.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-early%20development-f59e0b?style=flat-square" alt="Status: early development"/>
  <img src="https://img.shields.io/badge/minecraft-1.21.1-62a834?style=flat-square&logo=minecraft&logoColor=white" alt="Minecraft 1.21.1"/>
  <img src="https://img.shields.io/badge/neoforge-21.1.230-1f2937?style=flat-square" alt="NeoForge 21.1.230"/>
  <img src="https://img.shields.io/badge/kotlin-2.0.20-7f52ff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.0.20"/>
  <img src="https://img.shields.io/github/license/nitkanikita21/nodewire?style=flat-square&color=86c61e" alt="License: MIT"/>
  <img src="https://img.shields.io/github/actions/workflow/status/nitkanikita21/nodewire/build.yml?branch=port/neoforge-1.21.1&style=flat-square&label=build" alt="Build status"/>
</p>

> **Branch note.** `master` is the Forge 1.20.1 release (v0.1.x). The active branch [`port/neoforge-1.21.1`](https://github.com/nitkanikita21/nodewire/tree/port/neoforge-1.21.1) is the NeoForge 1.21.1 port (v0.2.0-dev) — what this README describes. Compiles + tests pass; in-client smoke testing in progress. Dev builds are published as GitHub pre-releases when a `dev-v*` tag is pushed.

## Features

- **Visual node editor** opened on a placed Logic Block. Pan, zoom, drag nodes, draw wires; inline pin-default editors; live values from the running block (singleplayer).
- **Unified pin linking** — one Channel Link Tool wires ANY pin-exposing block to any other: logic channels, cameras, screens, containers (item/fluid/comparator sensors), Aeronautics blocks, CBC cannon mounts, plain redstone faces. Links live on the consumer, re-deliver every tick, survive Sable structure motion.
- **Video system** — Camera blocks capture the world into VIDEO channels; Screen blocks display them, merge into multiblock panels (up to 8×8) and double as touch inputs. Scripts can transform video (overlays, HUDs, world→screen projection) between camera and screen.
- **Kotlin Script node** — write real Kotlin (`*.nw.kts`) compiled in-game by the optional `nodewire_scripting` addon: typed pins, persisted state, per-tick logic, JOML math (double precision end to end), video drawing with a flexbox UI DSL, sandboxed.
- **Create Big Cannons fire control** — live ballistic profiles from CBC's data-driven registry (addons included) + an exact-trajectory solver in scripts; cannon-mount orientation/position pins. Ships with ready fire-by-coordinates and gunsight scripts.
- **Unified selection model** — nodes, groups, and comments share the same press / drag / marquee / Del / accent-border logic; group/ungroup, nested groups, reusable templates, comments and wire labels.
- **Save & load graphs** to client-side files (`<gamedir>/nodewire-graphs/<name>.snbt`) and reuse them between worlds.
- **Cross-sub-level logic** — works across Sable sub-level boundaries (e.g., Aeronautics aircraft). Sable Companion provides safe no-op defaults so the mod runs without Sable installed.
- **More integrations** — Create wireless redstone, Aeronautics per-block channels (~33), Tweaked Controllers gamepad input, CC: Tweaked peripheral (Lua access to channels/graph), JEI/EMI ghost ingredients.

## Stack

- Minecraft **1.21.1** + NeoForge **21.1.230**
- Kotlin **2.0.20** + Kotlin for Forge (NeoForge) **5.5.0**
- Build plugin: **`net.neoforged.moddev` 2.0.141** (ModDevGradle, non-legacy)
- Java toolchain: **21**
- Custom UI framework on top of **Compose runtime 1.7.0** (no Skiko, no AWT) + **Yoga** (AppliedEnergistics fork, rebuilt for Java 21)
- Integrations: Sable (via Sable Companion), Create 6.0.10, Create Aeronautics 1.2.1, Create Big Cannons 5.11, Tweaked Controllers 1.2.7, CC: Tweaked, JEI, EMI
- Optional addon: **`nodewire_scripting`** — bundles the Kotlin compiler for in-game Script nodes (core works without it; script nodes then show a diagnostic)

## Build

```bash
./gradlew build      # compile + reobf, ~30s incremental
./gradlew test       # unit tests — run locally before pushing
```

> **CI note:** GitHub Actions runs `./gradlew build -x test` only. ModDevGradle's class remap doesn't refresh the SHA-384 digests in `META-INF/MANIFEST.MF`, and JarVerifier on a fresh CI cache rejects test discovery. Locally this is a non-issue.

ModDevGradle generates IDE run configurations automatically on Gradle sync — re-import the project in IntelliJ after editing `build.gradle.kts`. No manual `genIntellijRuns`.

The bundled `libs/yoga-1.0.0-j17.jar` is included so the project builds out of the box (the name is historical — Java 17 bytecode runs on the JDK 21 toolchain unchanged). To rebuild it yourself see [`docs/yoga-rebuild.md`](docs/yoga-rebuild.md).

## In-game

1. Launch the client (`./gradlew runClient` or via IntelliJ).
2. Enter a world.
3. Place the Nodewire block and right-click it to open the editor.

See [`docs/usage.md`](docs/usage.md) for the editing workflow, keybinds, node categories, and mod-integration details.

## Project layout

```
src/main/kotlin/dev/nitka/nodewire/
├── Nodewire.kt                 # @Mod entrypoint
├── Registry.kt                 # DeferredRegister for blocks/items/BEs
├── JpmsBridge.kt               # runtime kotlin.stdlib -> kotlinx.coroutines.core add_reads
├── block/                      # LogicBlock(+Entity), Screen, Camera, panels/touch, legacy bindings
├── link/                       # unified pin linking: PinPort, PinPorts, PinLink, PinLinkEngine
├── endpoint/                   # EndpointRef + backends (world / Sable sub-levels)
├── item/                       # ChannelLinkToolItem (LINK / PANEL modes)
├── graph/                      # graph model: Node, Edge, NodeGraph, Group, Comment, codecs, evaluators
├── script/                     # script API facade (ScriptModule, Vec*, JOML interop, Cbc ballistics, ui{} DSL)
├── net/                        # CustomPacketPayload-based network layer
├── client/
│   ├── NodewireClient.kt       # client init, keybinds
│   ├── screen/                 # node editor screens, script editor, Link Manager, pickers
│   ├── camera/                 # camera capture, world→screen projection
│   ├── video/                  # VideoManager (FBO surfaces, refcount GC), script video drawing
│   └── wire/                   # in-world wire rendering
├── integration/
│   ├── sable/                  # SableSubLevelBackend (replaces VS2)
│   ├── aeronautics/            # AeroChannel pipeline + AeroInput node
│   ├── cbc/                    # CBC ballistics provider + cannon-mount pins
│   ├── sensor/                 # capability sensor readings
│   ├── create/                 # redstone-link IO nodes
│   ├── cctweaked/              # Lua peripheral
│   └── tweakedcontroller/      # ControllerInput node
└── ui/                         # custom Compose runtime UI framework
    ├── core/                   # Applier, dispatcher, owner, YogaNode wiring
    ├── components/             # Text, TextInput, TextArea, Button, Surface, etc.
    ├── modifier/               # Modifier elements (layout / style / input)
    ├── scroll/                 # ScrollState + verticalScroll
    └── render/                 # NwCanvas + render walk

scripting/                      # optional addon: in-game Kotlin compiler, sandbox, bundled libs
```

Architectural details (UI framework layering, JPMS bridge, gotchas) live in [`CLAUDE.md`](CLAUDE.md). Port status and per-phase work log in [`MIGRATION_STATUS.md`](MIGRATION_STATUS.md).

## Contributing

This project is in early development. If you'd like to contribute:

1. Open an issue first to discuss the change.
2. Match existing patterns — look at neighbouring files before introducing new abstractions.
3. Add unit tests where reasonable; the existing test suite (under `src/test/kotlin`) covers graph codecs, group operations, proxy-pin labeling, and the Aeronautics channel catalog.

## License

[MIT](LICENSE) © 2026 nitka
