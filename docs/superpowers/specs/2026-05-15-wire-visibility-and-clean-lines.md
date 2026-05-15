# Wire Visibility & Clean Lines — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Make in-world binding rendering useful again:
1. **Gate on Channel Link Tool in hand** — wires + face frames only render when the player holds `ChannelLinkToolItem` in main hand or off hand. Currently always-on.
2. **Remove channel-name labels** — drop the dark plate + text labels at each wire endpoint. The colored line itself is the only visual.
3. **Lines visible through walls** — switch the wire RenderType from `LEQUAL_DEPTH_TEST` to `NO_DEPTH_TEST`. Since wires now only appear with the tool in hand, the through-wall mode aids inspection of the whole network.

## Non-goals

- No new naming features (separate sub-project).
- No block highlight system (separate sub-project).
- No style changes to wire thickness / color palette.
- No outline on endpoint blocks.

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt`

## Implementation outline

### Guard at top of `render(event)`

After the `event.stage` check and `mc.level` lookup, before `tracked` collection:

```kotlin
val player = mc.player ?: return
if (!holdingLinkTool(player)) return
```

Where:

```kotlin
private fun holdingLinkTool(player: LocalPlayer): Boolean {
    val link = Registry.CHANNEL_LINK_TOOL.get()
    return player.mainHandItem.`is`(link) || player.offhandItem.`is`(link)
}
```

(`ItemStack.is(Item)` is the 1.20.1 way; if that doesn't compile, use `.item == link`.)

### Remove labels

Delete:
- The `renderLabels(...)` function call at end of `render()`.
- The function `renderLabels` itself.
- The function `drawLabel`.
- The data classes `EndpointInfo`, `SideEndpointInfo`.
- The `endpoints` and `sideEndpoints` `ArrayList` collection sites in `render()` (the loop bodies stop calling `.add(...)`).
- The font import (`net.minecraft.client.gui.Font`) if no other reference remains.
- The `MultiBufferSource.BufferSource` parameter on any helper that only existed for labels.

Wires + face frames stay as-is — just stop building/drawing the label pass.

### Through-wall lines

Change the `WIRE_TYPE` RenderType definition: replace `Shards.LEQUAL_DEPTH` with `RenderStateShard.NO_DEPTH_TEST`. Drop the cached `LEQUAL_DEPTH` field from `Shards` if no other usage.

The same `WIRE_TYPE` is used for both quad wires AND face-frame rectangles (drawn via the same builder). Both inherit the new depth mode, which is fine — face frames also become through-wall visible. Acceptable side effect.

## Tests

None — pure renderer. Smoke checklist for the hand-off:
- Open game, place two logic blocks, no Channel Link Tool in inventory → no wires visible anywhere.
- Pick up `Channel Link Tool` (creative menu → Nodewire tab) → bindings appear as colored lines.
- Drop the tool / put in chest → wires disappear instantly.
- Bind logic block A → logic block B (Shift+RMB block A, pick channel, RMB block B, pick matching input). Hold tool — line appears.
- Bind logic block A → vanilla redstone lamp side. Hold tool — line + face frame appear.
- Stand behind a wall blocking line of sight to the bindings — still visible (through-wall).

## Out of scope (deferred)

- Subtle "fade-in" animation when starting to hold the tool.
- Per-binding-kind toggle (channel vs side filter).
- Block highlight pulse on endpoints when hovering a wire mid-air.
