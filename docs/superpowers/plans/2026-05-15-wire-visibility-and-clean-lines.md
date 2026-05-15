# Wire Visibility & Clean Lines — Implementation Plan

> **For agentic workers:** Stay on `master`. Single task, single commit.

**Goal:** Gate `WireWorldRenderer` on holding `ChannelLinkToolItem`, remove label rendering, switch to through-wall depth mode.

**Spec:** `docs/superpowers/specs/2026-05-15-wire-visibility-and-clean-lines.md`

**File:** `src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt`

---

### Task 1: Gate + delete labels + through-wall depth

- [ ] **Step 1: Add `holdingLinkTool(player)` helper + early return guard**

At the top of `render(event)`, after the existing `mc.level ?: return` line and BEFORE `val tracked = ClientLogicBlockTracker.all()`, add:

```kotlin
val player = mc.player ?: return
if (!holdingLinkTool(player)) return
```

Add the helper as a private method on the object:

```kotlin
private fun holdingLinkTool(player: net.minecraft.client.player.LocalPlayer): Boolean {
    val link = dev.nitka.nodewire.Registry.CHANNEL_LINK_TOOL.get()
    return player.mainHandItem.`is`(link) || player.offhandItem.`is`(link)
}
```

(If `ItemStack.is(Item)` doesn't resolve, fall back to `player.mainHandItem.item === link || player.offhandItem.item === link`.)

- [ ] **Step 2: Remove label rendering code**

In `render(event)`:
- Delete the `renderLabels(event, bufferSource, cameraPos, endpoints, sideEndpoints)` call line.
- Delete the `val endpoints = ArrayList<EndpointInfo>(bindList.size)` and `val sideEndpoints = ArrayList<SideEndpointInfo>(sideList.size)` allocations.
- Delete the `.add(...)` calls inside the wire-drawing loops that populate those lists.

Delete (file-level, not inside `render`):
- The whole `private fun renderLabels(...)` function.
- The whole `private fun drawLabel(...)` function.
- The `data class EndpointInfo(...)`.
- The `data class SideEndpointInfo(...)` if present.

After deletion, run `grep -n "endpoints\|sideEndpoints\|EndpointInfo\|drawLabel\|renderLabels\|Font" src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt` — there should be zero matches. If any helper imports become unused (like `net.minecraft.client.gui.Font`, `Component`, math `atan2`/`sqrt` used only by `drawLabel`), remove them. Kotlin compile errors will guide.

- [ ] **Step 3: Switch wire RenderType to NO_DEPTH_TEST**

In the `Shards` companion object, the `LEQUAL_DEPTH = LEQUAL_DEPTH_TEST` constant is no longer needed.

Replace `.setDepthTestState(Shards.LEQUAL_DEPTH)` in the `WIRE_TYPE` builder with `.setDepthTestState(RenderStateShard.NO_DEPTH_TEST)` (the constant is on `RenderStateShard`).

Delete the now-unused `val LEQUAL_DEPTH = LEQUAL_DEPTH_TEST` line in `Shards`.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If compile fails because `Font` / `Component` / `atan2` / `sqrt` imports are now unused — remove them.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt
git commit -m "$(cat <<'EOF'
feat(wire): gate render on Channel Link Tool, drop labels, through-wall lines

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Hand-off

User smoke test (per spec):
1. No Channel Link Tool → no wires visible.
2. Hold Channel Link Tool → wires + face frames appear.
3. Behind a wall → still visible (through-wall depth).
4. Drop tool → wires disappear immediately.
