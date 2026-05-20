#!/usr/bin/env bash
# Emit a markdown dependency table for the release notes.
# Derives versions from gradle.properties + build.gradle.kts so the
# table always matches what was actually built.

set -uo pipefail

prop() {
  grep "^$1=" gradle.properties 2>/dev/null | head -1 | cut -d= -f2- || true
}

MC=$(prop minecraft_version)
NEOFORGE=$(prop neoforge_version)
KFF=$(prop kff_version)

# Build.gradle.kts uses Kotlin string templates ($mcVer) inside dep
# coords. Resolve them to plain strings up front so plain grep can
# match the literal artifact name without escaping headaches.
NORMALIZED=$(sed "s|\${mcVer}|${MC:-1.21.1}|g; s|\$mcVer|${MC:-1.21.1}|g" build.gradle.kts)

# Match a `"group:name:VERSION"` literal and return just VERSION
# (drops any trailing classifier like ":slim"). Emits a placeholder
# when the dep isn't present so the table still renders.
dep_ver() {
  local needle="$1"
  local match
  match=$(printf '%s\n' "$NORMALIZED" | grep -oE "[\"']${needle}:[^\"']+[\"']" | head -1 || true)
  if [[ -z "$match" ]]; then
    echo "(not bundled)"
    return
  fi
  printf '%s\n' "$match" \
    | tr -d "\"'" \
    | sed -E "s|^${needle}:||" \
    | cut -d: -f1
}

KOTLIN=$(grep -oE 'id\("org\.jetbrains\.kotlin\.jvm"\) version "[^"]+"' build.gradle.kts | grep -oE '"[0-9][^"]+"' | tr -d '"' || true)
MDG=$(grep -oE 'id\("net\.neoforged\.moddev"\) version "[^"]+"' build.gradle.kts | grep -oE '"[0-9][^"]+"' | tr -d '"' || true)

SABLE_COMP=$(dep_ver "dev.ryanhcode.sable-companion:sable-companion-common-1.21.1")
SABLE=$(dep_ver "maven.modrinth:sable")
AERO=$(dep_ver "maven.modrinth:create-aeronautics")
CREATE=$(dep_ver "com.simibubi.create:create-${MC:-1.21.1}")
PONDER=$(dep_ver "net.createmod.ponder:ponder-neoforge")
FLYWHEEL_API=$(dep_ver "dev.engine-room.flywheel:flywheel-neoforge-api-${MC:-1.21.1}")
REGISTRATE=$(dep_ver "com.tterrag.registrate:Registrate")
JEI=$(dep_ver "mezz.jei:jei-${MC:-1.21.1}-neoforge")
EMI=$(dep_ver "dev.emi:emi-neoforge")
TWEAKED=$(dep_ver "curse.maven:create-tweaked-controllers-898849")
COMPOSE=$(dep_ver "org.jetbrains.compose.runtime:runtime")
COROUTINES=$(dep_ver "org.jetbrains.kotlinx:kotlinx-coroutines-core")
MIXINEXTRAS=$(dep_ver "io.github.llamalad7:mixinextras-neoforge")

cat <<MD
## Dependencies

### Runtime

| Dependency        | Version           | Required? |
| ----------------- | ----------------- | --------- |
| Minecraft         | \`${MC:-?}\`      | yes       |
| NeoForge          | \`${NEOFORGE:-?}\` | yes       |
| Kotlin for Forge  | \`${KFF:-?}\`     | yes       |

### Mod integrations (optional, enable extra nodes / features)

| Mod                  | Version       |
| -------------------- | ------------- |
| Sable Companion      | \`${SABLE_COMP}\` |
| Sable                | \`${SABLE}\`  |
| Create Aeronautics   | \`${AERO}\`   |
| Create               | \`${CREATE}\` |
| Ponder               | \`${PONDER}\` |
| Flywheel             | \`${FLYWHEEL_API}\` |
| Registrate           | \`${REGISTRATE}\` |
| JEI                  | \`${JEI}\`    |
| EMI                  | \`${EMI}\`    |
| Tweaked Controllers  | \`${TWEAKED}\` |

### Bundled (shaded into the jar)

| Library            | Version       |
| ------------------ | ------------- |
| Compose runtime    | \`${COMPOSE}\` |
| Kotlin coroutines  | \`${COROUTINES}\` |
| Yoga (AE2 fork, j17 bytecode) | \`1.0.0\` |
| MixinExtras        | \`${MIXINEXTRAS}\` |

### Build toolchain

| Tool             | Version    |
| ---------------- | ---------- |
| Kotlin           | \`${KOTLIN:-?}\` |
| ModDevGradle     | \`${MDG:-?}\` |
| Java toolchain   | \`21\`     |
MD
