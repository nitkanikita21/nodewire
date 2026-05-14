# Bindings Manager redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current two-line list inside `BindingsManagerScreen` with a grouped tree (per source channel), clickable group headers as source-pick, single-line target rows, and the real `Button(ButtonDefaults.danger())` for delete.

**Architecture:** Single-file rewrite of `client/screen/BindingsManagerScreen.kt`. Public constructor (`sourceBe`, `onPickSource`) unchanged so the call from `ChannelLinkToolItem` stays intact. No network/data-model changes — existing `RemoveBindingPacket` and `LogicBlockEntity.remove*` are reused. A small pure helper (`sideGlyph()` mapping `Direction → String`) is unit-tested; everything else is Compose UI verified by compile + visual check.

**Tech Stack:** Kotlin 2.0.20, custom Compose runtime + Yoga UI framework, MC 1.20.1 + Forge.

---

## File Structure

- Create: none
- Modify:
  - `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt` — full rewrite of the composable body. Keep the existing public class signature.
- Test:
  - `src/test/kotlin/dev/nitka/nodewire/client/screen/SideGlyphTest.kt` — unit test for the new `sideGlyph` helper.

Source-of-truth references (don't edit, but read for context):
- `src/main/kotlin/dev/nitka/nodewire/ui/components/Button.kt` — `ButtonDefaults.danger()`, `ButtonStyle.copy(...)`.
- `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwTheme.kt` + sibling files — `dimens.space2/4/6/8/12`, `colors.surfaceHover`, `colors.surfacePressed`, `colors.border`, `colors.danger`, `colors.onSurfaceMuted`, `typography.caption/subtitle`, `shapes.medium`, `dimens.borderThin`.
- `src/main/kotlin/dev/nitka/nodewire/block/{LogicBlockEntity,ChannelBinding,SideBinding}.kt` — fields the screen reads.
- `src/main/kotlin/dev/nitka/nodewire/net/RemoveBindingPacket.kt` + `NodewireNetwork.kt` — packet wired in.
- The existing `BindingsManagerScreen.kt` — preserve the outside-click-to-dismiss `Box(fillMaxSize).pointerInput` wrapper, the panel-centering math, and the `pinColor(PinType)` helper.

---

### Task 1: Add `sideGlyph` helper + unit test

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt` (add top-level private fun)
- Create: `src/test/kotlin/dev/nitka/nodewire/client/screen/SideGlyphTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/client/screen/SideGlyphTest.kt
package dev.nitka.nodewire.client.screen

import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideGlyphTest {
    @Test fun upIsArrow()    = assertEquals("↑", sideGlyph(Direction.UP))
    @Test fun downIsArrow()  = assertEquals("↓", sideGlyph(Direction.DOWN))
    @Test fun northIsN()     = assertEquals("N", sideGlyph(Direction.NORTH))
    @Test fun southIsS()     = assertEquals("S", sideGlyph(Direction.SOUTH))
    @Test fun westIsW()      = assertEquals("W", sideGlyph(Direction.WEST))
    @Test fun eastIsE()      = assertEquals("E", sideGlyph(Direction.EAST))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.SideGlyphTest"`
Expected: compilation error — `sideGlyph` not found (or `FAILED 6 of 6` if it compiles in some lenient state).

- [ ] **Step 3: Add the helper to `BindingsManagerScreen.kt`**

Insert before `private const val PANEL_WIDTH = ...` at the bottom of the file:

```kotlin
/**
 * Single-glyph label for a [Direction] when rendered inside a side-binding
 * target row. UP/DOWN get unicode arrows; cardinal directions use single
 * letters because horizontal arrows on a 2D screen are ambiguous without a
 * world-axis legend.
 */
internal fun sideGlyph(face: net.minecraft.core.Direction): String = when (face) {
    net.minecraft.core.Direction.UP    -> "↑"
    net.minecraft.core.Direction.DOWN  -> "↓"
    net.minecraft.core.Direction.NORTH -> "N"
    net.minecraft.core.Direction.SOUTH -> "S"
    net.minecraft.core.Direction.WEST  -> "W"
    net.minecraft.core.Direction.EAST  -> "E"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.nitka.nodewire.client.screen.SideGlyphTest"`
Expected: 6 of 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt \
        src/test/kotlin/dev/nitka/nodewire/client/screen/SideGlyphTest.kt
git commit -m "feat(ui): sideGlyph helper for binding direction labels"
```

---

### Task 2: Extract `Header` + `EmptyState` composables, drop old `Section`/`Header`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

This task narrows the existing screen down to the new top-of-panel layout. We'll re-wire the body in Task 5; for now compile-check the new header pieces in isolation.

- [ ] **Step 1: Replace the existing `Header()` composable**

In `BindingsManagerScreen`, replace the existing `private fun Header()` with this one (note: it now takes counts and renders the inline subtitle):

```kotlin
@Composable
private fun PanelHeader(channelCount: Int, bindingCount: Int) {
    val pos = sourceBe.blockPos
    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
        Row(verticalAlignment = Alignment.Center) {
            Text("Link Manager", style = NwTheme.typography.subtitle)
            Box(modifier = Modifier.weight(1f))
            Text(
                "$channelCount channels · $bindingCount bindings",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
        Text(
            "Block (${pos.toShortString()}) · click a channel to arm tool, ✕ to disconnect",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}
```

- [ ] **Step 2: Delete the old `Section(title, content)` composable**

It's unused after the new layout. Remove the function declaration from the file. (Keep the `Empty` text helper for now — it'll be reused.)

- [ ] **Step 3: Replace `EmptyRow` with `MutedLine`**

The old `EmptyRow` is fine, just rename for clarity since we'll use it both for empty-state and for the "no bindings" hint inside an idle channel group:

```kotlin
@Composable
private fun MutedLine(text: String) {
    Text(
        text,
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
    )
}
```

(Delete the old `EmptyRow` function. Update any remaining references to `EmptyRow` in the file by replacing the call name with `MutedLine`. We'll wire everything cleanly in Task 5.)

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. (The file may have unused references — that's fine; we fix them in Task 5.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "refactor(ui): PanelHeader + MutedLine for bindings manager"
```

---

### Task 3: Add `GroupHeader` composable (clickable channel row)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

- [ ] **Step 1: Add the composable**

Insert this top-level `@Composable` function near the other private composables at the bottom of the file:

```kotlin
/**
 * Clickable channel-output header. Whole row is the click target — picks
 * this channel as source and closes the screen. Idle channels (no bindings)
 * render with a thin border + reduced opacity to read as "available but
 * unused" without screaming for attention.
 */
@Composable
private fun GroupHeader(
    name: String,
    type: PinType,
    bindingCount: Int,
    onPick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val idle = bindingCount == 0
    val bg = when {
        hovered -> NwTheme.colors.surfacePressed
        idle    -> NwTheme.colors.surface  // transparent-ish via border
        else    -> NwTheme.colors.surfaceHover
    }
    val border = if (idle) BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .let { if (border != null) it else it } // border not yet — see note
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onPick(); true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Box(modifier = Modifier.size(7).background(pinColor(type), NwTheme.shapes.medium))
        Text(name, style = NwTheme.typography.caption)
        Text(
            type.name.lowercase(),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            if (bindingCount == 0) "no bindings"
            else "$bindingCount binding${if (bindingCount == 1) "" else "s"}",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}
```

Note on the `border` plumbing: the spec calls for a thin border around idle headers. Check what's available in `src/main/kotlin/dev/nitka/nodewire/ui/modifier/style/BorderModifier.kt` — if there's a `Modifier.border(width, color, shape)` extension, append it where the placeholder `.let { ... }` sits. If not (no border modifier), drop the placeholder; idle channels will be distinguished by the `surface` (vs. `surfaceHover`) background alone, which still reads as muted.

- [ ] **Step 2: Verify border availability**

Run: `grep -n "fun Modifier.border" src/main/kotlin/dev/nitka/nodewire/ui/modifier/style/BorderModifier.kt`
Expected: either a signature line (use it) or empty output (omit border).

- [ ] **Step 3: Wire the border conditionally**

If grep returned a `Modifier.border(...)` extension, replace the `.let { if (border != null) it else it }` placeholder with:

```kotlin
            .let { if (border != null) it.border(border.width, border.color, NwTheme.shapes.medium) else it }
```

If no border extension exists, simply delete the placeholder line.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "feat(ui): GroupHeader composable for channel outputs"
```

---

### Task 4: Rewrite target row + replace hand-rolled delete button

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

- [ ] **Step 1: Add `Button` import**

Ensure these are imported at the top of the file (add any missing ones):

```kotlin
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.ButtonStyle
import dev.nitka.nodewire.ui.layout.PaddingValues
```

- [ ] **Step 2: Replace `BindingRow`, `ChannelBindingRow`, `SideBindingRow`, `RemoveButton`**

Delete the old `BindingRow(...)`, `ChannelBindingRow(...)`, `SideBindingRow(...)`, and `RemoveButton(...)` functions in the file. Add this single replacement:

```kotlin
/**
 * One indented row under a [GroupHeader]. Always single-line. The `✕` is a
 * real [Button] with the danger preset, so hover/pressed states match the
 * rest of the UI.
 */
@Composable
private fun TargetRow(description: String, kindChip: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NwTheme.dimens.space16, end = NwTheme.dimens.space2, top = NwTheme.dimens.space2, bottom = NwTheme.dimens.space2),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Text(
            "→",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        Text(description, style = NwTheme.typography.caption)
        Box(modifier = Modifier.weight(1f))
        Text(
            kindChip,
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            modifier = Modifier
                .background(NwTheme.colors.surfacePressed, NwTheme.shapes.medium)
                .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2),
        )
        Button(
            onClick = onRemove,
            style = ButtonDefaults.danger().copy(
                padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
            ),
        ) {
            Text("×", style = NwTheme.typography.caption)
        }
    }
}
```

- [ ] **Step 3: Confirm `Text(... , modifier = ...)` is supported**

Run: `grep -n "fun Text" src/main/kotlin/dev/nitka/nodewire/ui/components/Text.kt`
Expected: an overload accepting `modifier: Modifier`.
If not present, wrap the kindChip Text in a `Box` instead:

```kotlin
        Box(
            modifier = Modifier
                .background(NwTheme.colors.surfacePressed, NwTheme.shapes.medium)
                .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2),
        ) {
            Text(kindChip, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
        }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "feat(ui): TargetRow with real Button(danger) for disconnect"
```

---

### Task 5: Rewrite `Panel` to iterate channel_outputs as groups

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

This is where the new layout actually shows up on screen. The body iterates every `channel_output` node on the source BE, renders a `GroupHeader`, then any matching channel-bindings and side-bindings as indented `TargetRow`s underneath.

- [ ] **Step 1: Replace the `Panel()` composable**

Replace the existing `private fun Panel()` body with:

```kotlin
@Composable
private fun Panel() {
    val mc = Minecraft.getInstance()
    val w = mc.window.guiScaledWidth
    val h = mc.window.guiScaledHeight

    // Bump on every successful remove so snapshots re-read.
    var version by remember { mutableStateOf(0) }

    val outputs = remember(version) {
        sourceBe.graph.nodes.values
            .filter { it.typeKey.path == "channel_output" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) null
                else Triple(name, PinType.fromName(node.config.getString("type")), node.id)
            }
    }
    val bindings = remember(version) { sourceBe.bindingsSnapshot() }
    val sideBindings = remember(version) { sourceBe.sideBindingsSnapshot() }
    val totalBindings = bindings.size + sideBindings.size

    Box(
        modifier = Modifier
            .padding(start = ((w - PANEL_WIDTH) / 2), top = ((h - 260).coerceAtLeast(20) / 2)),
    ) {
        Surface(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .pointerInput { ev, _, _ -> ev is PointerEvent.Press },
            style = SurfaceStyle(
                color = NwTheme.colors.surface,
                shape = NwTheme.shapes.medium,
                border = BorderStroke(1, NwTheme.colors.border),
                padding = PaddingValues(NwTheme.dimens.space8),
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6)) {
                PanelHeader(channelCount = outputs.size, bindingCount = totalBindings)

                if (outputs.isEmpty()) {
                    MutedLine("Add a Channel Output node to this block first.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                        for ((name, type, _) in outputs) {
                            val myChannelBindings = bindings.filter { it.sourceChannelName == name }
                            val mySideBindings = sideBindings.filter { it.sourceChannelName == name }
                            val count = myChannelBindings.size + mySideBindings.size

                            Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
                                GroupHeader(name = name, type = type, bindingCount = count) {
                                    onPickSource(name)
                                    Minecraft.getInstance().setScreen(null)
                                }
                                for (b in myChannelBindings) {
                                    TargetRow(
                                        description = "(${b.targetPos.toShortString()}) ${b.targetChannelName}",
                                        kindChip = "ch",
                                    ) {
                                        NodewireNetwork.CHANNEL.sendToServer(
                                            RemoveBindingPacket(
                                                sourcePos = sourceBe.blockPos,
                                                sourceChannelName = b.sourceChannelName,
                                                targetPos = b.targetPos,
                                                kind = RemoveBindingPacket.Kind.CHANNEL,
                                                extra = b.targetChannelName,
                                            ),
                                        )
                                        version++
                                    }
                                }
                                for (sb in mySideBindings) {
                                    TargetRow(
                                        description = "(${sb.targetPos.toShortString()}) ${sideGlyph(sb.targetSide)}",
                                        kindChip = "side",
                                    ) {
                                        NodewireNetwork.CHANNEL.sendToServer(
                                            RemoveBindingPacket(
                                                sourcePos = sourceBe.blockPos,
                                                sourceChannelName = sb.sourceChannelName,
                                                targetPos = sb.targetPos,
                                                kind = RemoveBindingPacket.Kind.SIDE,
                                                extra = sb.targetSide.name,
                                            ),
                                        )
                                        version++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Delete the now-unused old composables**

Remove from the file: `SourceRow`, `SourceList`, `ExistingList`, `totalBindings()` (any leftover from the previous version). Keep `pinColor`, `PanelHeader`, `MutedLine`, `GroupHeader`, `TargetRow`, `sideGlyph`, and `PANEL_WIDTH`.

- [ ] **Step 3: Bump `PANEL_WIDTH` to 400**

```kotlin
private const val PANEL_WIDTH = 400
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL with no unresolved references.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "feat(ui): grouped-tree layout for bindings manager"
```

---

### Task 6: Full build + visual smoke

**Files:** none (verification only).

- [ ] **Step 1: Full build with tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Confirms `SideGlyphTest` still passes and nothing else regressed.

- [ ] **Step 2: Stage and ask user to client-test**

Stop the loop here: this is a UI change in MC; visual correctness can only be checked in-game by the user. Post a short message:

> "Plan tasks complete. Open the client, place a logic block with a couple of `Channel Output` nodes, shift + right-click it with the Channel Link Tool — the manager should show grouped channels. Let me know what to adjust."

No commit on this step — the previous task's commit captures the work.

---

## Self-Review

**Spec coverage check:**

| Spec item                                  | Task |
|--------------------------------------------|------|
| Grouped tree, one row per channel_output   | 5    |
| Click on group header arms source          | 5 (GroupHeader callback) + 3 (composable) |
| Single-line target rows                    | 4    |
| `Button(ButtonDefaults.danger())` for ✕    | 4    |
| Visual distinction wired vs idle           | 3 (bg + optional border) |
| Counts in header `N channels · M bindings` | 2 (PanelHeader) + 5 (call site) |
| Side icon mapping                          | 1 (`sideGlyph`)  |
| Empty state: no `channel_output` nodes     | 5 (`MutedLine`) |
| Outside-click / ESC closes                 | unchanged from old screen — kept in `Content()` wrapper |
| Coords as `(x, y, z)`                      | 5 (`"(${pos.toShortString()})"`) |
| Panel width 400                            | 5 (`PANEL_WIDTH`) |

**Placeholder scan:** none. Each step has either runnable code or an exact command + expected output.

**Type consistency:** `GroupHeader(name, type, bindingCount, onPick)` — same signature used in Task 5. `TargetRow(description, kindChip, onRemove)` — same in Task 5. `sideGlyph(face: Direction): String` — same in Task 1 test and Task 5 caller. `PanelHeader(channelCount, bindingCount)` — same in Task 2 + Task 5.

**Ambiguities resolved inline:** the optional border (Task 3) and `Text(modifier=...)` overload (Task 4) each have a guarded fallback — grep first, then either wire it or omit it.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-14-bindings-manager-redesign.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between tasks, fastest iteration on small UI tweaks.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

**Which approach?**
