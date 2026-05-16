# Create Redstone Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two new node types (`redstone_link_input` / `redstone_link_output`) that read from and write to Create's redstone-link network via a 2-item-pair frequency picker.

**Architecture:** Nodes always registered (graphs survive Create absence); evaluators no-op via `ModList.isLoaded("create")` gate. Server-side `NodeLinkable` per (BE, nodeId) adapts Create's `IRedstoneLinkable`; transient map on `LogicBlockEntity`, cleared on `setRemoved`. UI = 2 ghost slots that accept JEI/EMI drag AND a click-opened inline inventory popover.

**Tech Stack:** Kotlin 2.0.20, Forge 1.20.1, Create 6.0.8 (`com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler`, `Frequency`, `IRedstoneLinkable`), JEI 15.20 (`IGhostIngredientHandler`), EMI (drag-drop API), Compose runtime, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-17-create-redstone-link-design.md`

---

## File Structure

**New:**
- `src/main/kotlin/dev/nitka/nodewire/integration/create/CreateRedstoneLink.kt` — all Create API contact (`frequencyOf`, `strongestSignal`, `NodeLinkable`, register/unregister/update helpers).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt` — inline inventory popover composable + slot widget composable.
- `src/main/kotlin/dev/nitka/nodewire/integration/jei/NodewireJeiPlugin.kt` — JEI plugin entry + ghost ingredient handler.
- `src/main/kotlin/dev/nitka/nodewire/integration/emi/NodewireEmiPlugin.kt` — EMI plugin entry + drag-drop handler.
- `src/test/kotlin/dev/nitka/nodewire/graph/RedstoneLinkNodeTypesTest.kt` — registration smoke + default-config shape.

**Modified:**
- `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — add `REDSTONE_LINK_INPUT`, `REDSTONE_LINK_OUTPUT`; include in `registerAll`.
- `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` — add `RedstoneLinkInput`, `RedstoneLinkOutput` no-op evaluators (server-tick does the real work).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — add `RedstoneLinkFrequency` composable variant.
- `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt` — read input nodes inside `serverTick` external collection; manage transient `linkables: MutableMap<UUID, NodeLinkable>`; cleanup in `setRemoved`.

---

## Phase 1 — Node types + skeletons (no Create runtime contact)

### Task 1: Register `REDSTONE_LINK_INPUT` + `REDSTONE_LINK_OUTPUT` + skeleton evaluators

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` (stub composable)
- Test: Create `src/test/kotlin/dev/nitka/nodewire/graph/RedstoneLinkNodeTypesTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/graph/RedstoneLinkNodeTypesTest.kt
package dev.nitka.nodewire.graph

import net.minecraft.world.item.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedstoneLinkNodeTypesTest {
    @BeforeEach fun reset() {
        NodeTypeRegistry.clearForTests()
        StockNodeTypes.registerAll()
    }

    @Test fun `redstone_link_input is registered with REDSTONE output`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_input"))
        assertNotNull(t)
        assertEquals(0, t!!.inputs.size)
        assertEquals(1, t.outputs.size)
        assertEquals(PinType.REDSTONE, t.outputs[0].type)
    }

    @Test fun `redstone_link_output is registered with REDSTONE input`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_output"))
        assertNotNull(t)
        assertEquals(1, t!!.inputs.size)
        assertEquals(0, t.outputs.size)
        assertEquals(PinType.REDSTONE, t.inputs[0].type)
    }

    @Test fun `default config has empty freq1 and freq2 compounds`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_input"))!!
        val cfg = t.defaultConfig()
        assertTrue(cfg.contains("freq1"))
        assertTrue(cfg.contains("freq2"))
        // Both decode back to ItemStack.EMPTY
        assertTrue(ItemStack.of(cfg.getCompound("freq1")).isEmpty)
        assertTrue(ItemStack.of(cfg.getCompound("freq2")).isEmpty)
    }
}
```

- [ ] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.RedstoneLinkNodeTypesTest" -i`
Expected: FAIL — types not registered.

If `NodeTypeRegistry.clearForTests()` doesn't exist, check the registry file. If absent, add an internal test-only helper before proceeding, similar to `EndpointBackends.clearForTests()`. Pattern:

```kotlin
// inside NodeTypeRegistry
internal fun clearForTests() { /* clear the map */ }
```

- [ ] **Step 3: Add skeleton composable in `NodeConfigContent.kt`**

Add inside `object NodeConfigContent`, near `ChannelEndpoint`:

```kotlin
    /**
     * Two ghost slots side-by-side for the Redstone Link frequency pair.
     * Slot rendering + popover handled by [RedstoneLinkSlotPicker] (added
     * in a later phase). For now this is a placeholder that just renders
     * two empty 18×18 boxes so the node card has correct layout.
     */
    val RedstoneLinkFrequency: @Composable (Node) -> Unit = { node ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(4),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4),
        ) {
            Text("Frequency")
            Box(modifier = Modifier
                .padding(left = 8)
                .background(NwTheme.colors.surfaceVariant)
                .border(BorderStroke(1, NwTheme.colors.outline))
                .padding(all = 9),
            ) {}
            Box(modifier = Modifier
                .background(NwTheme.colors.surfaceVariant)
                .border(BorderStroke(1, NwTheme.colors.outline))
                .padding(all = 9),
            ) {}
        }
    }
```

(If `padding(all = 9)` doesn't compile against existing API — use `padding(horizontal = 9, vertical = 9)` or whatever the existing `padding` modifier signature is. Inspect `ui/modifier/layout/padding.kt` to verify the exact API.)

- [ ] **Step 4: Add skeleton evaluators in `StockEvaluators.kt`**

Append near `ChannelOutput` / `ChannelInput`:

```kotlin
    /**
     * RedstoneLinkInput: server tick will override via external inputs map
     * (reads from Create's network). Evaluator just exposes a typed default
     * so downstream graph nodes get a value before the first tick.
     */
    val RedstoneLinkInput: NodeEvaluator = { _, _ ->
        mapOf("out" to PinValue.Redstone(0))
    }

    /**
     * RedstoneLinkOutput: no-op evaluator. Server tick reads the incoming
     * edge value and pushes it onto the Create network via a NodeLinkable
     * adapter — the evaluator itself has nothing to produce.
     */
    val RedstoneLinkOutput: NodeEvaluator = { _, _ -> emptyMap() }
```

- [ ] **Step 5: Register the node types in `StockNodeTypes.kt`**

Append two new vals after `CHANNEL_OUTPUT`:

```kotlin
    val REDSTONE_LINK_INPUT = nodeType(
        id = "redstone_link_input",
        displayName = "Redstone Link Input",
        category = NodeCategory.IO,
        outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
        defaultConfig = {
            CompoundTag().apply {
                put("freq1", net.minecraft.world.item.ItemStack.EMPTY.save(CompoundTag()))
                put("freq2", net.minecraft.world.item.ItemStack.EMPTY.save(CompoundTag()))
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.RedstoneLinkFrequency,
        evaluate = StockEvaluators.RedstoneLinkInput,
    )

    val REDSTONE_LINK_OUTPUT = nodeType(
        id = "redstone_link_output",
        displayName = "Redstone Link Output",
        category = NodeCategory.IO,
        inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
        defaultConfig = {
            CompoundTag().apply {
                put("freq1", net.minecraft.world.item.ItemStack.EMPTY.save(CompoundTag()))
                put("freq2", net.minecraft.world.item.ItemStack.EMPTY.save(CompoundTag()))
            }
        },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.RedstoneLinkFrequency,
        evaluate = StockEvaluators.RedstoneLinkOutput,
    )
```

Add them to the `registerAll` list under the `// IO` comment line:

```kotlin
            // IO
            SIDE_INPUT, SIDE_OUTPUT, CHANNEL_INPUT, CHANNEL_OUTPUT,
            REDSTONE_LINK_INPUT, REDSTONE_LINK_OUTPUT,
```

- [ ] **Step 6: Run tests, verify pass**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.RedstoneLinkNodeTypesTest" -i && ./gradlew build`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/RedstoneLinkNodeTypesTest.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): register input/output node types with skeleton UI

Nodes always registered so graphs survive Create absence; evaluators
will pull from / push to Create's redstone-link network in later
phases. Config schema: freq1 + freq2 CompoundTags wrapping ItemStacks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Create network integration (server side)

### Task 2: `CreateRedstoneLink.kt` — Create API contact

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/create/CreateRedstoneLink.kt`

This file is **the only place** that imports Create API for redstone-link work. Compiled unconditionally (Create is `modImplementation`, always on classpath), but called only when `ModList.isLoaded("create")` is true.

- [ ] **Step 1: Probe Create API surface first**

```bash
javap -classpath "$(find ~/.gradle/caches -name 'create-1.20.1-6.0.8-289*.jar' -not -name '*sources*' | head -1)" \
    com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler 2>&1 | head -40
javap -classpath "$(find ~/.gradle/caches -name 'create-1.20.1-6.0.8-289*.jar' -not -name '*sources*' | head -1)" \
    com.simibubi.create.content.redstone.link.IRedstoneLinkable 2>&1 | head -30
javap -classpath "$(find ~/.gradle/caches -name 'create-1.20.1-6.0.8-289*.jar' -not -name '*sources*' | head -1)" \
    com.simibubi.create.content.redstone.link.RedstoneLinkFrequencySlot 2>&1 | head -20
javap -classpath "$(find ~/.gradle/caches -name 'create-1.20.1-6.0.8-289*.jar' -not -name '*sources*' | head -1)" \
    com.simibubi.create.foundation.utility.Couple 2>&1 | head -20
javap -classpath "$(find ~/.gradle/caches -name 'create-1.20.1-6.0.8-289*.jar' -not -name '*sources*' | head -1)" \
    com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity 2>&1 | grep -i "network\|frequency" | head -10
```

Use the output to verify exact method signatures for: `addToNetwork`, `removeFromNetwork`, `updateNetworkOf`, the public `networks` field on the handler, `Frequency.of(ItemStack)`, `Couple.create(T, T)`, `IRedstoneLinkable.getNetworkKey/getTransmittedStrength/setReceivedStrength/isAlive/getLocation`. If any signature differs from what's in this plan, adjust your implementation to match the actual API.

- [ ] **Step 2: Implement `CreateRedstoneLink.kt`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/integration/create/CreateRedstoneLink.kt
package dev.nitka.nodewire.integration.create

import com.simibubi.create.content.redstone.link.IRedstoneLinkable
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency
import com.simibubi.create.foundation.utility.Couple
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * All Create-API contact for the redstone-link integration lives here.
 * Callers in core code must gate calls with `ModList.isLoaded("create")`;
 * this class itself does not check — once you call any function, you're
 * committed to Create being present on the classpath at runtime.
 */
object CreateRedstoneLink {

    /** Decode a `Couple<Frequency>` from a node's config tag (`freq1`, `freq2`). */
    fun frequencyOf(cfg: CompoundTag): Couple<Frequency> {
        val s1 = ItemStack.of(cfg.getCompound("freq1"))
        val s2 = ItemStack.of(cfg.getCompound("freq2"))
        return Couple.create(Frequency.of(s1), Frequency.of(s2))
    }

    /**
     * Return the strongest transmitted signal currently on [freq] in [level],
     * or 0 if no transmitter present. Iterates the network's linkables.
     */
    fun strongestSignal(level: Level, freq: Couple<Frequency>): Int {
        val handler = handlerFor(level) ?: return 0
        val network = handler.getNetworkOf(level, freq) ?: return 0
        var max = 0
        for (linkable in network) {
            val v = linkable.transmittedStrength
            if (v > max) max = v
        }
        return max
    }

    /** Adapter that puts one (BE, nodeId) on a Create redstone-link network. */
    class NodeLinkable(
        private val be: LogicBlockEntity,
        @Volatile var freq: Couple<Frequency>,
        @Volatile var lastTransmit: Int = 0,
    ) : IRedstoneLinkable {
        override fun getNetworkKey(): Couple<Frequency> = freq
        override fun getTransmittedStrength(): Int = lastTransmit
        override fun setReceivedStrength(value: Int) { /* output-only */ }
        override fun isAlive(): Boolean = !be.isRemoved
        override fun getLocation(): BlockPos = be.blockPos
    }

    fun register(level: Level, linkable: NodeLinkable) {
        handlerFor(level)?.addToNetwork(level, linkable)
    }

    fun unregister(level: Level, linkable: NodeLinkable) {
        handlerFor(level)?.removeFromNetwork(level, linkable)
    }

    fun updateNetworkOf(level: Level, linkable: NodeLinkable) {
        handlerFor(level)?.updateNetworkOf(level, linkable)
    }

    /**
     * Access the per-level network handler. Create stores it on the
     * server's MinecraftServer instance via `Create.REDSTONE_LINK_NETWORK_HANDLER`
     * (singleton on the server side). On the client, signal is mirrored via
     * synced redstone-link blocks; for our use we only care about server-side
     * reads/writes so this returns null on the client.
     */
    private fun handlerFor(level: Level): RedstoneLinkNetworkHandler? {
        if (level.isClientSide) return null
        return com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER
    }
}
```

**Verify against Step 1's `javap` output.** If `Frequency` is a top-level class instead of a nested one inside `RedstoneLinkNetworkHandler`, adjust the import. If `Create.REDSTONE_LINK_NETWORK_HANDLER` is package-private or named differently, locate the accessor (search `grep -r "REDSTONE_LINK" sources.jar` after extracting). Common alternatives: `Create.redstoneLinkNetworkHandler`, instance access via `Create.INSTANCE.redstoneLinkNetworkHandler`.

- [ ] **Step 3: Build to confirm compile**

Run: `./gradlew build`
Expected: SUCCESS (or compile error pointing to a signature mismatch; resolve per Step 2 notes).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/create/CreateRedstoneLink.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): Create API adapter (frequency + NodeLinkable)

Isolates all imports from com.simibubi.create.* into one file.
NodeLinkable adapts our (BE, nodeId) pair to Create's IRedstoneLinkable
interface; strongestSignal queries the network for input nodes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Wire input read into `LogicBlockEntity.serverTick`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

- [ ] **Step 1: Add ModList import**

In `LogicBlockEntity.kt` import block, add:

```kotlin
import net.minecraftforge.fml.ModList
```

- [ ] **Step 2: Add redstone_link_input branch to external collection**

In `serverTick`, find the external-inputs `for (node in graph.nodes.values) { when (node.typeKey.path) { ... } }` block (look for the existing `"side_input"` and `"channel_input"` branches). Add a third branch:

```kotlin
                "redstone_link_input" -> {
                    if (!ModList.get().isLoaded("create")) continue
                    val freq = dev.nitka.nodewire.integration.create.CreateRedstoneLink.frequencyOf(node.config)
                    val signal = dev.nitka.nodewire.integration.create.CreateRedstoneLink.strongestSignal(level, freq)
                    external[node.id to "out"] = PinValue.Redstone(signal)
                }
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): wire input nodes into server-tick external inputs

Each redstone_link_input node polls Create's network for its configured
frequency every tick and pushes the strongest transmitter signal as
PinValue.Redstone. Gated on ModList.isLoaded("create").

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Output linkable lifecycle on `LogicBlockEntity`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

- [ ] **Step 1: Add transient linkables map**

Inside the `LogicBlockEntity` class body, near other transient fields (e.g. `serverEvaluator`):

```kotlin
    /**
     * Per-node Create redstone-link adapters. Transient — rebuilt from the
     * graph on demand each tick; cleared in [setRemoved] so we don't leave
     * dead linkables in Create's network handler.
     */
    private val linkables: MutableMap<java.util.UUID, dev.nitka.nodewire.integration.create.CreateRedstoneLink.NodeLinkable> =
        mutableMapOf()
```

- [ ] **Step 2: Add output-update helper inside the class**

```kotlin
    private fun updateRedstoneLinkOutputs(level: Level, result: dev.nitka.nodewire.graph.StatefulGraphEvaluator.Result) {
        val CR = dev.nitka.nodewire.integration.create.CreateRedstoneLink
        val seen = HashSet<java.util.UUID>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "redstone_link_output") continue
            seen.add(node.id)
            val desiredFreq = CR.frequencyOf(node.config)
            val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
            val value = edge?.let { result.valueAt(it.from.node, it.from.pin) }
            val strength = redstoneOf(value)
            val existing = linkables[node.id]
            if (existing == null) {
                val l = CR.NodeLinkable(this, desiredFreq, strength)
                linkables[node.id] = l
                CR.register(level, l)
            } else if (existing.freq != desiredFreq) {
                CR.unregister(level, existing)
                existing.freq = desiredFreq
                existing.lastTransmit = strength
                CR.register(level, existing)
            } else {
                existing.lastTransmit = strength
            }
            CR.updateNetworkOf(level, linkables[node.id]!!)
        }
        // Unregister orphans (nodes deleted by the user).
        val orphans = linkables.keys - seen
        for (id in orphans) {
            linkables.remove(id)?.let { CR.unregister(level, it) }
        }
    }
```

If `StatefulGraphEvaluator.Result` is the wrong type name, look it up in `StatefulGraphEvaluator.kt` — the existing tick code already binds the eval result to a `result` local; mirror the same type.

- [ ] **Step 3: Call helper from `serverTick`**

In `serverTick`, after the existing `eval.tick(external)` line and before the channel-binding propagation block at the bottom, add:

```kotlin
        if (ModList.get().isLoaded("create")) {
            updateRedstoneLinkOutputs(level, result)
        }
```

- [ ] **Step 4: Add cleanup in `setRemoved`**

Inside the existing `override fun setRemoved()` body, BEFORE the `super.setRemoved()` call but inside the server-side branch (i.e. inside the `else { val lvl = level; if (lvl != null) { ... } }` block — same place where `VirtualSignalMap.clearSource` is called), append:

```kotlin
                if (ModList.get().isLoaded("create")) {
                    val CR = dev.nitka.nodewire.integration.create.CreateRedstoneLink
                    for ((_, l) in linkables) CR.unregister(lvl, l)
                    linkables.clear()
                }
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): output node lifecycle on LogicBlockEntity

Transient linkables map per BE. updateRedstoneLinkOutputs (called each
tick after eval) registers/unregisters/reconfigures NodeLinkable per
node, prunes orphans whose node was deleted. setRemoved drops all
linkables so Create's network doesn't see ghost transmitters.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — Frequency slot picker UI

### Task 5: `RedstoneLinkSlotPicker.kt` — slot widget + inline popover

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` — replace the Phase 1 stub with a call into `RedstoneLinkSlotPicker`.

- [ ] **Step 1: Implement slot widget + popover**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.overlay.LocalOverlayHost
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * Render two ghost slots ("Frequency"). Each slot:
 *   - Shows the current ItemStack (icon + count) or empty placeholder.
 *   - LMB → open popover (player-inventory grid + search).
 *   - RMB → clear the slot.
 *
 * Slot rect is registered with [RedstoneLinkSlotRegistry] so JEI/EMI
 * ghost-drag handlers can target it in screen-space (later phase).
 */
@Composable
fun RedstoneLinkFrequencySlots(node: Node, editor: EditorState?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4),
    ) {
        Text("Frequency")
        FrequencySlot(node, editor, slotKey = "freq1")
        FrequencySlot(node, editor, slotKey = "freq2")
    }
}

@Composable
private fun FrequencySlot(node: Node, editor: EditorState?, slotKey: String) {
    val overlay = LocalOverlayHost.current
    val currentStack = ItemStack.of(node.config.getCompound(slotKey))

    Box(
        modifier = Modifier
            .size(18)
            .background(NwTheme.colors.surfaceVariant)
            .border(BorderStroke(1, NwTheme.colors.outline))
            .onPositioned { coords ->
                RedstoneLinkSlotRegistry.update(node.id, slotKey, coords) { stack ->
                    setSlot(node, editor, slotKey, stack)
                }
            }
            .pointerInput { ev ->
                when {
                    ev is PointerEvent.Click && ev.button == PointerEvent.Button.LEFT -> {
                        openPicker(overlay, node, editor, slotKey)
                    }
                    ev is PointerEvent.Click && ev.button == PointerEvent.Button.RIGHT -> {
                        setSlot(node, editor, slotKey, ItemStack.EMPTY)
                    }
                }
            }
            .onHover { /* tooltip handled inside ItemSlotRenderer below */ },
    ) {
        if (!currentStack.isEmpty) {
            ItemSlotIcon(currentStack)
        }
    }
}

@Composable
private fun ItemSlotIcon(stack: ItemStack) {
    // Use the existing IconRenderer / vanilla GuiGraphics.renderItem inside
    // NwCanvas. Concrete pattern: see ui/render/IconRenderer.kt — if it
    // supports item rendering, call it. If not, fall back to a coloured
    // square + textual id (e.g. "DIAM" for diamond) so the slot still shows
    // *something*. Either way the user can hover for the tooltip handled
    // below.
    //
    // Implementation guidance: NwCanvas wraps GuiGraphics; check whether
    // `nwCanvas.graphics.renderItem(stack, x, y)` is available via the
    // existing Renderer abstraction in ui/render/Renderer.kt. If yes, add
    // a small ItemRenderer modifier that calls it. If the renderer doesn't
    // expose an item-render hook yet, add one to ui/render/Renderer.kt as a
    // small focused change (the modifier needs ~10 lines).
    //
    // Tooltip: register the slot's display name with the active tooltip
    // overlay using the existing onHover pattern in TextRenderer.kt.
    Box(modifier = Modifier.size(16).background(NwTheme.colors.primary)) {
        // Placeholder rendering — replace with real item icon per above.
    }
}

private fun setSlot(node: Node, editor: EditorState?, slotKey: String, stack: ItemStack) {
    editor?.updateNode(node.id) { n ->
        n.copy(config = n.config.copy().apply {
            put(slotKey, stack.save(CompoundTag()))
        })
    }
}

private fun openPicker(
    overlay: dev.nitka.nodewire.ui.overlay.OverlayState,
    node: Node,
    editor: EditorState?,
    slotKey: String,
) {
    overlay.show {
        InventoryPickerPopover(
            onPick = { picked ->
                setSlot(node, editor, slotKey, picked)
                overlay.hide()
            },
            onDismiss = { overlay.hide() },
        )
    }
}

@Composable
private fun InventoryPickerPopover(
    onPick: (ItemStack) -> Unit,
    onDismiss: () -> Unit,
) {
    val inventory = remember { Minecraft.getInstance().player?.inventory?.items?.toList().orEmpty() }
    var query by remember { mutableStateOf("") }
    val unique = remember(query) {
        val seen = HashSet<String>()
        inventory.filter {
            if (it.isEmpty) return@filter false
            val key = it.item.descriptionId
            if (!seen.add(key)) return@filter false
            query.isEmpty() || it.hoverName.string.contains(query, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .background(NwTheme.colors.surface)
            .border(BorderStroke(1, NwTheme.colors.outline))
            .padding(all = 6),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4)) {
            TextInput(
                value = query,
                placeholder = "Search...",
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
            )
            // Render a 9-wide grid; chunk the unique list into rows.
            val rows = unique.chunked(9)
            for (row in rows.take(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2)) {
                    for (stack in row) {
                        Box(
                            modifier = Modifier
                                .size(18)
                                .background(NwTheme.colors.surfaceVariant)
                                .border(BorderStroke(1, NwTheme.colors.outline))
                                .pointerInput { ev ->
                                    if (ev is PointerEvent.Click && ev.button == PointerEvent.Button.LEFT) {
                                        onPick(stack.copyWithCount(1))
                                    }
                                },
                        ) {
                            ItemSlotIcon(stack)
                        }
                    }
                }
            }
        }
    }
}
```

**Notes:**
- This file may not compile cleanly against existing UI primitives — the `pointerInput`/`onHover` modifier shapes, `Box.size(Int)`, `Modifier.onPositioned`, `LocalOverlayHost.current` all need to match what's actually exported. **Read each file in `ui/modifier/` and `ui/overlay/` before writing code blocks above; adjust calls to match.** If an API doesn't exist (e.g. `Modifier.size(Int)`), use the closest equivalent or extend the modifier package minimally.
- `LocalOverlayHost.current` must exist on `ui/overlay/OverlayHost.kt` as a composition local. If absent, the overlay system uses a different access pattern — adapt.
- The "ItemSlotIcon" stub renders a coloured square placeholder. Wiring it to vanilla `GuiGraphics.renderItem` requires extending the renderer; **leave that as a TODO inside the file body and pick it up in Task 6** if it's bigger than ~30 LOC, or do it inline if the existing `IconRenderer` already supports items.

- [ ] **Step 2: Replace `NodeConfigContent.RedstoneLinkFrequency` stub**

In `NodeConfigContent.kt`, replace the Phase 1 stub with a delegating call:

```kotlin
    val RedstoneLinkFrequency: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        RedstoneLinkFrequencySlots(node, editor)
    }
```

- [ ] **Step 3: Add `RedstoneLinkSlotRegistry` stub**

Create as a stub for now — JEI/EMI will populate it in Phase 4:

```kotlin
// inside RedstoneLinkSlotPicker.kt or as a sibling object
internal object RedstoneLinkSlotRegistry {
    data class Slot(val rect: net.minecraft.client.gui.navigation.ScreenRectangle, val accept: (ItemStack) -> Unit)
    private val slots = mutableMapOf<Pair<java.util.UUID, String>, Slot>()
    fun update(nodeId: java.util.UUID, slotKey: String, coords: dev.nitka.nodewire.ui.input.LayoutCoordinates, accept: (ItemStack) -> Unit) {
        slots[nodeId to slotKey] = Slot(
            net.minecraft.client.gui.navigation.ScreenRectangle(coords.screenX, coords.screenY, coords.width, coords.height),
            accept,
        )
    }
    fun all(): Collection<Slot> = slots.values
    fun clear() = slots.clear()
}
```

Adjust the `LayoutCoordinates` type/import to whatever the project already uses for `onPositioned` callbacks (look at `NodeCard.kt` or `CanvasState.kt` for the existing reference).

- [ ] **Step 4: Build and verify no compile errors**

Run: `./gradlew build`
Expected: SUCCESS, or compile errors that you resolve by adjusting modifier shapes to match the existing API surface.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): slot widget + inline inventory popover

Two ghost slots in the node config: LMB opens a player-inventory grid
with search filter, RMB clears. Slot rects registered for JEI/EMI to
target in screen-space. Item icon rendering is a placeholder pending
the IconRenderer extension; functionally complete otherwise.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Real item icon rendering

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/render/IconRenderer.kt` (extend or sibling) OR add a small `ItemSlotIconRenderer.kt`.
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt` (use the real renderer).

- [ ] **Step 1: Inspect the current `Renderer` / `IconRenderer` to see how it gets `GuiGraphics`**

```bash
sed -n '1,80p' src/main/kotlin/dev/nitka/nodewire/ui/render/NwCanvas.kt
sed -n '1,80p' src/main/kotlin/dev/nitka/nodewire/ui/render/IconRenderer.kt
```

`NwCanvas` wraps `GuiGraphics`; we need access to that to call `renderItem(stack, x, y)`. The simplest extension: add a `drawItem(stack: ItemStack, x: Int, y: Int)` method on `NwCanvas` that delegates to `graphics.renderItem(stack, x, y)` (plus `renderItemDecorations` for the count).

- [ ] **Step 2: Add `drawItem` to `NwCanvas`**

Add inside `NwCanvas`:

```kotlin
fun drawItem(stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) {
    graphics.renderItem(stack, x + offsetX, y + offsetY)
    graphics.renderItemDecorations(font, stack, x + offsetX, y + offsetY)
}
```

`offsetX`/`offsetY` are NwCanvas's existing offset-stack accumulators; find the actual field names and use those.

- [ ] **Step 3: Add an `ItemIcon` modifier or composable**

Easiest path: a small composable that draws into the canvas in a `LaunchedEffect` / `SideEffect` — but in this UI framework, drawing happens inside `PaintWalk.renderWalk(canvas)`. The pattern: define an `ItemIconRenderer` that's a `StyleModifierElement` and attaches via `Modifier`. Look at existing `IconRenderer.kt` for the exact pattern; clone it but call `canvas.drawItem(stack, 0, 0)` inside the render hook.

Pseudocode of a sibling file `ui/render/ItemIconRenderer.kt`:

```kotlin
package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.StyleModifierElement
import net.minecraft.world.item.ItemStack

internal class ItemIconElement(val stack: ItemStack) : StyleModifierElement<ItemIconElement> {
    override fun render(canvas: NwCanvas) {
        canvas.drawItem(stack, 0, 0)
    }
}

fun Modifier.itemIcon(stack: ItemStack): Modifier = this.then(ItemIconElement(stack))
```

Wire signatures depend on the exact `StyleModifierElement` interface — read `core/Modifier.kt` first.

- [ ] **Step 4: Use it in `ItemSlotIcon`**

In `RedstoneLinkSlotPicker.kt`, replace the placeholder Box with:

```kotlin
@Composable
private fun ItemSlotIcon(stack: ItemStack) {
    Box(modifier = Modifier.size(16).itemIcon(stack))
}
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/render/ \
        src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt
git commit -m "$(cat <<'EOF'
feat(ui): NwCanvas.drawItem + Modifier.itemIcon for slot rendering

Used by the redstone-link frequency slots. Wraps vanilla
GuiGraphics.renderItem + renderItemDecorations.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — JEI / EMI ghost-drag integration

### Task 7: JEI plugin

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/jei/NodewireJeiPlugin.kt`
- Modify: `META-INF/services/` entry registration if JEI uses it (verify via JEI docs)

JEI 15.x on 1.20.1 uses `@JeiPlugin` annotation processed at runtime. No META-INF changes needed.

- [ ] **Step 1: Probe JEI API symbols**

```bash
javap -classpath "$(find ~/.gradle/caches -name 'jei-1.20.1-forge-api-15.20*.jar' | head -1)" \
    mezz.jei.api.IModPlugin 2>&1 | head -20
javap -classpath "$(find ~/.gradle/caches -name 'jei-1.20.1-forge-api-15.20*.jar' | head -1)" \
    mezz.jei.api.gui.handlers.IGhostIngredientHandler 2>&1 | head -20
```

Confirm method names: `registerGuiHandlers`, `IGhostIngredientHandler.getTargetsTyped`, `IGhostIngredientHandler.Target` etc.

- [ ] **Step 2: Implement the plugin**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/integration/jei/NodewireJeiPlugin.kt
package dev.nitka.nodewire.integration.jei

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.client.screen.NodeEditorScreen
import dev.nitka.nodewire.client.screen.RedstoneLinkSlotRegistry
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.gui.handlers.IGhostIngredientHandler
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.registration.IGuiHandlerRegistration
import net.minecraft.client.renderer.Rect2i
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

@JeiPlugin
class NodewireJeiPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation = ResourceLocation(Nodewire.ID, "jei")

    override fun registerGuiHandlers(reg: IGuiHandlerRegistration) {
        reg.addGhostIngredientHandler(NodeEditorScreen::class.java, NodeEditorGhostHandler())
    }
}

private class NodeEditorGhostHandler : IGhostIngredientHandler<NodeEditorScreen> {
    override fun <I : Any> getTargetsTyped(
        screen: NodeEditorScreen,
        ingredient: ITypedIngredient<I>,
        doStart: Boolean,
    ): List<IGhostIngredientHandler.Target<I>> {
        val stack = ingredient.ingredient as? ItemStack ?: return emptyList()
        if (stack.isEmpty) return emptyList()
        return RedstoneLinkSlotRegistry.all().map { slot ->
            object : IGhostIngredientHandler.Target<I> {
                override fun getArea(): Rect2i =
                    Rect2i(slot.rect.position().x(), slot.rect.position().y(), slot.rect.width, slot.rect.height)
                @Suppress("UNCHECKED_CAST")
                override fun accept(ingredient: I) {
                    slot.accept(ingredient as ItemStack)
                }
            }
        }
    }

    override fun onComplete() {}
}
```

If `ScreenRectangle` API doesn't expose `.position()` / `.width` / `.height` exactly like above, use whatever it does expose (likely `.left`/`.top`/`.width`/`.height` or `.x`/`.y`). Adjust.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS. JEI classes are `modCompileOnly` so they compile; at runtime, the plugin is auto-discovered only if JEI is loaded.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/jei/NodewireJeiPlugin.kt
git commit -m "$(cat <<'EOF'
feat(redstone-link): JEI ghost-ingredient handler for frequency slots

Players can drag items from the JEI panel onto redstone-link frequency
slots in the node editor. Targets live in RedstoneLinkSlotRegistry.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: EMI plugin

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/integration/emi/NodewireEmiPlugin.kt`

EMI's ghost-drag API uses `EmiDragDropHandler`. Symbol probe first.

- [ ] **Step 1: Probe EMI API**

```bash
find ~/.gradle/caches -name 'emi*1.20.1*.jar' -not -name '*sources*' | head -3
javap -classpath "$(find ~/.gradle/caches -name 'emi*1.20.1*.jar' -not -name '*sources*' | head -1)" \
    dev.emi.emi.api.EmiPlugin 2>&1 | head -20
javap -classpath "$(find ~/.gradle/caches -name 'emi*1.20.1*.jar' -not -name '*sources*' | head -1)" \
    dev.emi.emi.api.EmiRegistry 2>&1 | grep -i "drag\|ghost" | head -10
javap -classpath "$(find ~/.gradle/caches -name 'emi*1.20.1*.jar' -not -name '*sources*' | head -1)" \
    dev.emi.emi.api.EmiDragDropHandler 2>&1 | head -10
```

- [ ] **Step 2: Implement the plugin using whatever API the probe reveals**

Skeleton — adapt to actual API:

```kotlin
// src/main/kotlin/dev/nitka/nodewire/integration/emi/NodewireEmiPlugin.kt
package dev.nitka.nodewire.integration.emi

import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.stack.EmiStack
import dev.nitka.nodewire.client.screen.NodeEditorScreen
import dev.nitka.nodewire.client.screen.RedstoneLinkSlotRegistry
import net.minecraft.world.item.ItemStack

class NodewireEmiPlugin : EmiPlugin {
    override fun register(registry: EmiRegistry) {
        registry.addDragDropHandler(NodeEditorScreen::class.java) { screen, emiStack, mouseX, mouseY ->
            val stack = (emiStack as? EmiStack)?.itemStack as? ItemStack ?: return@addDragDropHandler false
            if (stack.isEmpty) return@addDragDropHandler false
            for (slot in RedstoneLinkSlotRegistry.all()) {
                val r = slot.rect
                if (mouseX in r.left()..r.right() && mouseY in r.top()..r.bottom()) {
                    slot.accept(stack.copyWithCount(1))
                    return@addDragDropHandler true
                }
            }
            false
        }
    }
}
```

EMI plugin discovery: add to `META-INF/services/dev.emi.emi.api.EmiPlugin`:

```bash
mkdir -p src/main/resources/META-INF/services
echo "dev.nitka.nodewire.integration.emi.NodewireEmiPlugin" > src/main/resources/META-INF/services/dev.emi.emi.api.EmiPlugin
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/integration/emi/NodewireEmiPlugin.kt \
        src/main/resources/META-INF/services/dev.emi.emi.api.EmiPlugin
git commit -m "$(cat <<'EOF'
feat(redstone-link): EMI drag-drop handler for frequency slots

Mirror of the JEI plugin for EMI users. Discoverability via
META-INF/services entry.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — Final validation + manual handoff

### Task 9: Full test suite + manual test plan

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS.

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 3: Hand off manual test plan to the user**

**Do not** run `./gradlew runClient`. Report this list to the user for them to execute:

1. Place 2 LogicBlocks on the ground. In the first, add `Redstone Link Output` node; wire `Constant=15` → its `in`. In the second, add `Redstone Link Input`; wire its `out` → a `Side Output` driving e.g. an adjacent redstone lamp.
2. Open editor on each block, set both freq slots to the same pair via the popover (LMB on slot → search → click). Save (close editor).
3. Lamp should light. If not — server log should not show any errors; check that the `out` strength is 15.
4. Add a vanilla Create Redstone Link Receiver with the same freq → it should also receive 15.
5. Change one freq slot → lamp goes dark.
6. Open JEI panel, drag an item onto a freq slot → slot updates. Same with EMI if installed.
7. RMB freq slot → cleared.
8. Place LogicBlock on a VS ship; bind its `Redstone Link Output` to a world-block lamp's frequency. Lamp should still light (network is per-Level).
9. Disable Create, reload world → bindings preserved in NBT, no crash, warning indicator (if implemented) visible.

### Task 10: Commit handoff notes (only if any final tweaks emerged)

If any of the manual-test steps revealed small fixes during the run, commit them as `fix(redstone-link): ...`.

---

## Out of scope (separate sub-projects)

- **Tweaked Controllers input** — separate spec follows.
- **JEI focused-recipe lookup from freq slot (R/U key)** — follow-up.
- **Active-transmitters tooltip** — follow-up.
- **Item-icon-renderer extension to other widgets** — covered for slots only; reuse pattern when needed.
