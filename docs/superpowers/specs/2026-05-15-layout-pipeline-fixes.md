# Layout pipeline fixes

## Context

Audit of the custom Compose UI framework turned up five real bugs in the
**layout pipeline** — distinct from any individual component code. The
suspicion that "the layout math is broken" was correct. Components
(TextInput, Checkbox, Select, ContextMenu, popups) misbehave partly because
of these underlying defects; fix the pipeline first, then re-evaluate what
remains at the component level.

No third-party headless-UI library can help here. Compose's `foundation`
and `material3` modules both transitively depend on `compose.ui`, which we
can't pull in (no Skiko / no Java2D / no `androidx.compose.ui` runtime in
the Forge JPMS isolation). Our hand-rolled stack stays; this spec just
fixes it.

## Bugs in scope

Listed by impact, highest first.

### Bug B — `LocalScreenSize` is captured as zero on first composition

Files: `ui/theme/NwTheme.kt:52`, `ui/core/NwComposeScreen.kt:58`.

```kotlin
val LocalScreenSize = staticCompositionLocalOf { IntSize.Zero }
```

A `staticCompositionLocalOf` invalidates the whole subtree when its
value changes, but more importantly it reads its value *once* at the
provider site — and `owner.screenSize.value` is `IntSize.Zero` until the
first `frame()` ticks. Every consumer (`Popup`, `ContextMenu`,
`ToastHost`, the `Select` dropdown) sees `IntSize.Zero` on its first
composition. Popup placement uses screen width to decide edge-flip and
clamping, so all overlays open in wrong positions until a follow-up
recomposition happens to be triggered.

**Fix.** Switch to `compositionLocalOf` (dynamic) and provide
`owner.screenSize` (the State itself, not `.value`) via a `derivedStateOf`
in the screen, so the read actually subscribes to changes. Reading
`.current` then re-reads the State each composition.

### Bug A — `yogaConfig` is never reset between recompositions

File: `ui/layout/Layout.kt:33`, `ui/layout/YogaMapping.kt:21–30`.

```kotlin
set(yogaConfig) { yoga.apply(it) }
```

`UiNode.modifier`-setter resets touched Yoga properties (`YogaReset.kt`)
before re-applying the chain. But `yogaConfig` (the lambda parameter of
`Layout`, used by Row/Column/Text to set `justifyContent`, `gap`,
`measureFunc`, etc.) bypasses that reset. So a Row that switches from
`Arrangement.SpacedBy(8)` to `Arrangement.Start` keeps `gap=8` set in
Yoga — `applyArrangement(Arrangement.Start)` only writes
`justifyContent`, never resets gap.

**Fix.** Extend `YogaReset.resetYogaStyle` to also reset `gap`,
`justifyContent`, `alignItems`, `alignContent`, `alignSelf`,
`flexDirection`, `flexWrap` to their Yoga defaults. Call it *both* from
the modifier setter (as today) *and* before applying `yogaConfig`. The
two resets can share one function: `Layout` calls reset → applies
modifier → applies yogaConfig, in that order.

### Bug D — `OnSizeChangedModifier.lastSize` is reset every recomposition

File: `ui/modifier/input/OnSizeChangedModifier.kt:14`.

```kotlin
class OnSizeChangedModifier(val callback: ...) : ... {
    var lastSize: IntSize? = null   // instance field
}
```

`Modifier.then` builds a new linked list each composition: every
modifier instance is recreated. The de-dup field `lastSize` resets to
`null` every recomposition, so the callback fires on every recompose —
including when nothing about the layout actually changed. Callers that
write state from the callback (like `EditorState.setCardSize`) then
trigger another recomposition, and the cycle continues.

**Fix.** Move the dedup out of the modifier instance into the consumer.
Either:

- a) Call the user's callback always, and let the consumer compare —
  *or* —
- b) Store `lastSize` keyed by the owning `UiNode`, not the modifier
  object. Add a `WeakHashMap<UiNode, IntSize>` in the post-layout walk
  and only invoke the callback when the size differs.

Option (b) is the right one; (a) pushes the dedup work onto every
caller. Implement in `NwUiOwner`'s post-layout walk.

### Bug C — `.padding(a).padding(b)` silently last-wins

File: `ui/core/UiNode.kt:31–36`, `ui/modifier/layout/PaddingModifier.kt:16`.

`foldIn` calls each `LayoutModifierElement.applyTo(yoga)` in turn —
each writes the same Yoga property — the last one wins. `mergeWith` on
the modifier classes is dead code; nothing in the framework ever calls
it.

This is **not** a render-time bug (it's deterministic), but it's a
foot-gun: a reader might expect `.padding(4).padding(2)` to stack to 6
the way CSS-in-JS or Compose Material do.

**Fix (minimal).** Delete `mergeWith` overrides on
`PaddingModifier`, `MarginModifier`, `SizeModifier`, `FlexModifier`
since they're never called. Add a sentence to each modifier's KDoc:
"Multiple instances of this modifier in a chain are last-wins."
Document in `ui/modifier/README` if one exists; otherwise inline.

This is documentation, not behaviour. Treating it as a bug because the
mismatch between the dead `mergeWith` and actual behaviour is
misleading.

### Bug E — `absoluteOffset()` ignores scroll for drag routing

File: `ui/core/HitTester.kt:84–93`, `ui/core/NwUiOwner.kt:224–232`.

`absoluteOffset` sums `layoutX/layoutY` up the parent chain but doesn't
subtract any scroll offset. `NwUiOwner.routeToFocus` uses this to
compute `localX/localY` for drag events, so a drag inside a scrolled
container has its coordinates off by the scroll amount.

**Status.** We don't currently have any scrollable containers in the
mod's UI — the node-editor uses a NodeCanvas pan/zoom which already
hooks into `absoluteOffset` correctly. **Out of scope** for this spec.
File a future ticket when we add the first scroll container.

## Order of work

1. Bug B (`LocalScreenSize`) — fixes popups landing in wrong position.
2. Bug A (`yogaConfig` reset) — fixes stale Arrangement/Gap between
   recompositions.
3. Bug D (`OnSizeChangedModifier` dedup moved to owner) — fixes
   potential recomp loops, calms unrelated overhead.
4. Bug C — docstring cleanup + delete dead `mergeWith` overrides.

## Out of scope

- Any component-level redesign (TextInput / Checkbox / Select /
  ContextMenu). After the pipeline is fixed, re-test the components
  visually and file a follow-up spec for whatever still looks wrong.
- Adopting a third-party headless-Compose library — none exists for
  compose-runtime-only stacks. See the brainstorming research summary
  in the conversation history.
- Bug E. Filed in this spec for traceability but not fixed; revisit
  when we add the first scrollable container.

## Verification

- Manual: open `NodeEditorScreen`, right-click on canvas — context
  menu should now appear *adjacent* to the cursor on the first try
  (Bug B). Open a logic block twice in a row — overlays should look
  identical between sessions (no first-frame zero-screen-size jank).
- Manual: place two `Row { Arrangement.SpacedBy(8) ... }` instances on
  one screen, edit code to switch one to `Arrangement.Start`,
  hot-reload — gap should disappear, not stay (Bug A). Without an HMR
  setup, exercise by toggling `mutableStateOf` of the arrangement.
- Unit tests are difficult here because the pipeline is tied to Yoga
  + MC's render loop. Visual smoke is the practical test plan; the
  user runs `./gradlew runClient` and reports back.
- Add one unit test for `OnSizeChangedModifier`: mount → bump
  unrelated state → assert callback fired exactly once (the initial
  measurement), not once per recomposition.

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwTheme.kt` — Bug B.
- `src/main/kotlin/dev/nitka/nodewire/ui/core/NwComposeScreen.kt` — Bug B.
- `src/main/kotlin/dev/nitka/nodewire/ui/core/YogaReset.kt` — Bug A.
- `src/main/kotlin/dev/nitka/nodewire/ui/layout/Layout.kt` — Bug A.
- `src/main/kotlin/dev/nitka/nodewire/ui/core/NwUiOwner.kt` — Bug D.
- `src/main/kotlin/dev/nitka/nodewire/ui/modifier/input/OnSizeChangedModifier.kt` — Bug D.
- `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/PaddingModifier.kt` — Bug C (delete mergeWith).
- `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/MarginModifier.kt` — Bug C.
- `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/SizeModifier.kt` — Bug C.
- `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/FlexModifier.kt` — Bug C.
- New: `src/test/kotlin/dev/nitka/nodewire/ui/modifier/input/OnSizeChangedModifierTest.kt`.
