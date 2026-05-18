#!/usr/bin/env bash
# Emit a markdown dependency table for the release notes.
# Derives versions from gradle.properties + build.gradle.kts so the
# table always matches what was actually built.

set -euo pipefail

prop() { grep "^$1=" gradle.properties | head -1 | cut -d= -f2-; }

# Match a `module("group:name:VERSION")` literal in build.gradle.kts and
# return just VERSION (drops any trailing classifier like ":slim" or
# ":processor"). Falls back to a placeholder if the dep isn't present so the
# table still renders.
dep_ver() {
  local needle="$1"
  local line
  line=$(grep -E "[\"']${needle}:[^\"']+[\"']" build.gradle.kts | head -1) || {
    echo "::warning::dep_ver: '$needle' not found in build.gradle.kts" >&2
    echo "(not bundled)"
    return
  }
  # Strip everything up to and including the artifact-name, then drop any
  # classifier suffix after the version.
  printf '%s\n' "$line" \
    | grep -oE "${needle}:[^\"']+" \
    | head -1 \
    | sed -E "s|^${needle}:||" \
    | cut -d: -f1
}

MC=$(prop minecraft_version)
FORGE=$(prop forge_version)
KFF=$(prop kff_version)

KOTLIN=$(grep -oE 'id\("org\.jetbrains\.kotlin\.jvm"\) version "[^"]+"' build.gradle.kts | grep -oE '"[0-9][^"]+"' | tr -d '"')
MDG=$(grep -oE 'id\("net\.neoforged\.moddev\.legacyforge"\) version "[^"]+"' build.gradle.kts | grep -oE '"[0-9][^"]+"' | tr -d '"')

VS=$(dep_ver "org.valkyrienskies:valkyrienskies-120-forge")
CREATE=$(dep_ver "com.simibubi.create:create-1.20.1")
PONDER=$(dep_ver "net.createmod.ponder:Ponder-Forge-1.20.1")
FLYWHEEL=$(dep_ver "dev.engine-room.flywheel:flywheel-forge-1.20.1")
REGISTRATE=$(dep_ver "com.tterrag.registrate:Registrate")
JEI=$(dep_ver "mezz.jei:jei-1.20.1-forge")
EMI=$(dep_ver "dev.emi:emi-forge")
TWEAKED=$(dep_ver "maven.modrinth:create-tweaked-controllers")
COMPOSE=$(dep_ver "org.jetbrains.compose.runtime:runtime")
COROUTINES=$(dep_ver "org.jetbrains.kotlinx:kotlinx-coroutines-core")
MIXIN=$(dep_ver "org.spongepowered:mixin")
MIXINEXTRAS=$(dep_ver "io.github.llamalad7:mixinextras-forge")

cat <<MD
## Dependencies

### Runtime

| Dependency        | Version           | Required? |
| ----------------- | ----------------- | --------- |
| Minecraft         | \`$MC\`           | yes       |
| Forge             | \`$FORGE\`        | yes       |
| Kotlin for Forge  | \`$KFF\`          | yes       |

### Mod integrations (optional, enable extra nodes / features)

| Mod                  | Version       |
| -------------------- | ------------- |
| Valkyrien Skies 2    | \`$VS\`       |
| Create               | \`$CREATE\`   |
| Ponder               | \`$PONDER\`   |
| Flywheel             | \`$FLYWHEEL\` |
| Registrate           | \`$REGISTRATE\` |
| JEI                  | \`$JEI\`      |
| EMI                  | \`$EMI\`      |
| Tweaked Controllers  | \`$TWEAKED\`  |

### Bundled (shaded into the jar)

| Library            | Version       |
| ------------------ | ------------- |
| Compose runtime    | \`$COMPOSE\`  |
| Kotlin coroutines  | \`$COROUTINES\` |
| Yoga (AE2 fork, j17 rebuild) | \`1.0.0\`    |
| MixinExtras        | \`$MIXINEXTRAS\` |

### Build toolchain

| Tool             | Version    |
| ---------------- | ---------- |
| Kotlin           | \`$KOTLIN\` |
| ModDevGradle     | \`$MDG\`   |
| Java toolchain   | \`17\`     |
| Mixin            | \`$MIXIN\` |
MD
