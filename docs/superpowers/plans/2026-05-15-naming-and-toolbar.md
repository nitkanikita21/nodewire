# Naming & Editor Toolbar — Implementation Plan

> **For agentic workers:** Stay on `master`. 5 tasks, one commit each.

**Goal:** Add LogicBlock name, SideBinding name, and editor top toolbar with name field.

**Spec:** `docs/superpowers/specs/2026-05-15-naming-and-toolbar.md`

**Conventions:**
- Each task ends with `./gradlew build` BUILD SUCCESSFUL + one commit.
- TDD where unit-testable.
- No `--no-verify`.

---

### Task 1: `SideBinding` gains optional `name` field

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/block/SideBindingCodecTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package dev.nitka.nodewire.block

import com.mojang.serialization.JsonOps
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideBindingCodecTest {

    @Test
    fun roundtripsWithName() {
        val original = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH, "my label")
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
    }

    @Test
    fun roundtripsWithEmptyName() {
        val original = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH)
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
        assertEquals("", decoded.name)
    }

    @Test
    fun decodesLegacyNbtWithoutNameField() {
        // Build JSON object lacking "name" key — simulates pre-naming saves.
        val legacy = JsonObject().apply {
            addProperty("src", "chA")
            add("pos", JsonOps.INSTANCE.empty().let { _ ->
                // BlockPos.CODEC serializes as a 3-int list — easier to encode a full
                // SideBinding then strip the name key:
                val full = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH, "anything")
                val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, full).result().get().asJsonObject
                encoded.remove("name")
                return@let encoded.get("pos")
            })
            // Reuse the trick above to also embed direction encoded by the codec.
            val full = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH, "x")
            val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, full).result().get().asJsonObject
            encoded.remove("name")
            add("side", encoded.get("side"))
        }
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, legacy).result().get()
        assertEquals("", decoded.name)
    }
}
```

(If JSON manipulation gets messy, simpler: encode a SideBinding with `name="x"`, remove the `name` key from the JsonObject, decode and assert `name == ""`. Write the test however reads cleanest.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.nitka.nodewire.block.SideBindingCodecTest"` → FAIL (constructor doesn't accept a 4th arg; codec doesn't write/read `name`).

- [ ] **Step 3: Implement**

In `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`:

```kotlin
data class SideBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
    val name: String = "",
) {
    companion object {
        val CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter(SideBinding::targetPos),
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SideBinding::name),
            ).apply(i, ::SideBinding)
        }
    }
}
```

- [ ] **Step 4: Run tests — green. Commit.**

```bash
./gradlew test --tests "dev.nitka.nodewire.block.SideBindingCodecTest"
git add src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt \
        src/test/kotlin/dev/nitka/nodewire/block/SideBindingCodecTest.kt
git commit -m "$(cat <<'EOF'
feat(block): SideBinding gains optional name field (codec-roundtrip safe)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `LogicBlockEntity` name field + rename mutator for SideBinding

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`

**Context:** Read `LogicBlockEntity.kt` first. The file has `saveAdditional(tag)` and `load(tag)` methods around lines 358-405. `getUpdateTag()` may or may not override the default; verify by grep.

- [ ] **Step 1: Add `name` field + getter/setter**

Near the other private fields (around line 56 where `sideBindings` lives):

```kotlin
private var blockName: String = ""

fun getBlockName(): String = blockName

fun setBlockName(value: String) {
    val sanitized = value.take(MAX_NAME_LENGTH)
    if (blockName == sanitized) return
    blockName = sanitized
    setChanged()
    val l = level ?: return
    l.sendBlockUpdated(blockPos, blockState, blockState, 3)
}

private companion object {
    private const val MAX_NAME_LENGTH = 64
}
```

(If the class already has a `companion object`, merge the constant into it instead of creating a new one.)

- [ ] **Step 2: Persist in NBT**

In `saveAdditional(tag)`, after the existing writes, before the closing brace:

```kotlin
if (blockName.isNotEmpty()) {
    tag.putString("name", blockName)
}
```

In `load(tag)`, near the start (before the graph/bindings reads — order doesn't matter, just put it together):

```kotlin
blockName = tag.getString("name")  // returns "" if missing
```

- [ ] **Step 3: Verify `getUpdateTag` path covers `name`**

Search the file: `grep -n "getUpdateTag\|onDataPacket\|handleUpdateTag\|ClientboundBlockEntityDataPacket" src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`. If the class explicitly overrides any of these and constructs a partial tag manually, ensure `name` is also written into the sync tag. If the class relies on defaults (which delegate to `saveAdditional` for `getUpdateTag` and `load` for `handleUpdateTag`), no extra code needed.

- [ ] **Step 4: Add SideBinding rename mutator**

Near `addSideBinding`:

```kotlin
fun renameSideBinding(
    sourceChannelName: String,
    targetPos: BlockPos,
    targetSide: Direction,
    newName: String,
) {
    val idx = sideBindings.indexOfFirst {
        it.sourceChannelName == sourceChannelName
            && it.targetPos == targetPos
            && it.targetSide == targetSide
    }
    if (idx < 0) return
    val sanitized = newName.take(64)
    if (sideBindings[idx].name == sanitized) return
    sideBindings[idx] = sideBindings[idx].copy(name = sanitized)
    setChanged()
    val l = level ?: return
    l.sendBlockUpdated(blockPos, blockState, blockState, 3)
}
```

- [ ] **Step 5: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt
git commit -m "$(cat <<'EOF'
feat(block): LogicBlockEntity gains blockName + SideBinding rename mutator

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Network packets

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/net/SetBlockNamePacket.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/net/SetSideBindingNamePacket.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt`

**Context:** Read `BindChannelPacket.kt` and `BindSideChannelPacket.kt` for the existing Forge-network packet shape (encode/decode/handle pattern). Read `NodewireNetwork.kt` to see how messages are registered (`SimpleChannel.registerMessage(id, ...)` with incrementing IDs).

- [ ] **Step 1: `SetBlockNamePacket.kt`**

```kotlin
package dev.nitka.nodewire.net

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

class SetBlockNamePacket(val pos: BlockPos, val name: String) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeUtf(name, MAX_NAME_LEN)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>) {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return@enqueueWork
            be.setBlockName(name)
        }
        c.packetHandled = true
    }

    companion object {
        const val MAX_NAME_LEN = 64
        fun decode(buf: FriendlyByteBuf) =
            SetBlockNamePacket(buf.readBlockPos(), buf.readUtf(MAX_NAME_LEN))
    }
}
```

- [ ] **Step 2: `SetSideBindingNamePacket.kt`**

Same shape; payload is `sourcePos: BlockPos, sourceChannelName: String, targetPos: BlockPos, targetSide: Direction, name: String`. Handler calls `(getBlockEntity(sourcePos) as? LogicBlockEntity)?.renameSideBinding(...)`.

- [ ] **Step 3: Register in `NodewireNetwork.register()`**

Add the two `CHANNEL.registerMessage(id, ...)` calls with the next available IDs after the existing packets. Match the established pattern (decoder lambda, encode/handle method refs).

- [ ] **Step 4: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/net/SetBlockNamePacket.kt \
        src/main/kotlin/dev/nitka/nodewire/net/SetSideBindingNamePacket.kt \
        src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt
git commit -m "$(cat <<'EOF'
feat(net): SetBlockNamePacket + SetSideBindingNamePacket

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `EditorToolbar` + `EditorState` wiring + `NodeEditorScreen` layout

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`

**Context:** `EditorState` already uses `MutableStateFlow` extensively. `LocalEditorState` is a Compose CompositionLocal already defined. `NodeEditorScreen.Content()` builds the canvas inside a `Box`. Read all three files first.

- [ ] **Step 1: Add `blockName` flow + `setBlockName` to `EditorState`**

```kotlin
private val _blockName = kotlinx.coroutines.flow.MutableStateFlow("")
val blockName: kotlinx.coroutines.flow.StateFlow<String> = _blockName.asStateFlow()
fun setBlockName(name: String) { _blockName.value = name }
```

(Place near the other `_xxx`/`xxx` flow pairs.)

- [ ] **Step 2: Create `EditorToolbar.kt`**

```kotlin
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.nitka.nodewire.net.NodewireNetwork
import dev.nitka.nodewire.net.SetBlockNamePacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraftforge.network.PacketDistributor

@Composable
fun EditorToolbar(pos: BlockPos, onOpenBindings: () -> Unit) {
    val editor = LocalEditorState.current ?: return
    val name by editor.blockName.collectAsState()
    val scope = rememberCoroutineScope()
    val debouncer = remember { NameDebouncer(scope) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NwTheme.colors.surface)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        TextInput(
            modifier = Modifier.width(NAME_INPUT_WIDTH),
            value = name,
            onValueChange = { next ->
                editor.setBlockName(next)
                debouncer.schedule(pos, next)
            },
        )
        Box(modifier = Modifier.weight(1f))
        Button(onClick = onOpenBindings) { Text("Bindings…") }
    }
}

private class NameDebouncer(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var pending: Job? = null
    fun schedule(pos: BlockPos, name: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            NodewireNetwork.CHANNEL.send(
                PacketDistributor.SERVER.noArg(),
                SetBlockNamePacket(pos, name),
            )
        }
    }
    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}

private const val NAME_INPUT_WIDTH = 200
```

If `TextInput` has a `placeholder` parameter, set it to `"Logic Block (${pos.x}, ${pos.y}, ${pos.z})"`. If not — skip placeholder.

- [ ] **Step 3: Wrap `NodeEditorScreen.Content` in `Column` with toolbar**

The existing content lives inside `NwThemeProvider { ... Box(...) { canvas etc. } }`. Change to:

```kotlin
NwThemeProvider {
    val canvas = rememberCanvasState()
    val editor = remember(graph) {
        EditorState(graph, pos).also {
            editorRef = it
            // Initialize blockName from BE. mc.level might give us the BE
            // client-side; if it's null at this point, default to "".
            val be = net.minecraft.client.Minecraft.getInstance().level
                ?.getBlockEntity(pos) as? dev.nitka.nodewire.block.LogicBlockEntity
            it.setBlockName(be?.getBlockName() ?: "")
        }
    }
    // ... rest of evaluator + LaunchedEffect ...
    CompositionLocalProvider(
        LocalEditorState provides editor,
        LocalEvalResult provides evalResult,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorToolbar(pos = pos, onOpenBindings = {
                net.minecraft.client.Minecraft.getInstance().setScreen(
                    BindingsManagerScreen(
                        sourceBe = net.minecraft.client.Minecraft.getInstance().level
                            ?.getBlockEntity(pos) as? dev.nitka.nodewire.block.LogicBlockEntity
                            ?: return@EditorToolbar,
                        onPickSource = { },
                    ),
                )
            })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NwTheme.colors.background)
                    .pointerInput { /* existing handlers */ },
            ) {
                // existing NodeCanvas content
            }
        }
    }
}
```

**Important:** if `BindingsManagerScreen`'s `onPickSource` callback can't be no-op (i.e. it expects a real source-pick + close flow), pass a sensible no-op that does nothing on click. The screen itself uses the callback only when the user clicks a channel-output group header — viewing/removing bindings doesn't trigger it.

If `Modifier.fillMaxSize()` on the `Column` conflicts with the inner `Box.fillMaxSize()` (because the toolbar already takes 36px), use `Modifier.weight(1f)` on the inner `Box` instead. Compose conventions allow `weight` only inside `Column`/`Row` scopes — should work.

- [ ] **Step 4: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt
git commit -m "$(cat <<'EOF'
feat(editor): top toolbar with block-name input and Bindings shortcut

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `BindingsManagerScreen` — header name + inline side-binding name input

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

**Context:** Read the file. `PanelHeader` shows the block position. The side-binding `TargetRow` call site is around line 150 in the file.

- [ ] **Step 1: Update `PanelHeader` to use the block name**

Replace the line that builds the block position chip with logic that prefers `sourceBe.getBlockName()` over the short pos string, falling back to `"(${pos.toShortString()})"` when name is empty.

Specifically: in `PanelHeader`, change:

```kotlin
Text(
    "Block (${pos.toShortString()}) · click a channel to arm tool, ✕ to disconnect",
    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
)
```

to:

```kotlin
val displayName = sourceBe.getBlockName().ifBlank { "(${pos.toShortString()})" }
Text(
    "Block $displayName · click a channel to arm tool, ✕ to disconnect",
    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
)
```

- [ ] **Step 2: Add inline name input for side bindings**

In `TargetRow`'s signature (already has `targetPos: BlockPos` from the highlight feature), add another optional parameter:

```kotlin
@Composable
private fun TargetRow(
    description: String,
    kindChip: String,
    targetPos: BlockPos,
    bindingName: String = "",
    onRename: ((String) -> Unit)? = null,
    onRemove: () -> Unit,
) {
```

If `onRename != null` (side-binding case), render a small `TextInput` before the `description` text. Width 100px. `onValueChange` calls a debounced helper that sends `SetSideBindingNamePacket`. Use the same `NameDebouncer` pattern as the toolbar — define it locally in this file (or extract to a shared util later).

Display logic: when `bindingName` is non-empty, show `bindingName` as the row's primary label; the existing `description` becomes a muted secondary line. When empty, render only the description.

Call site for side-binding row (around line 150):

```kotlin
TargetRow(
    description = "(${sb.targetPos.toShortString()}) ${sideGlyph(sb.targetSide)}",
    kindChip = "side",
    targetPos = sb.targetPos,
    bindingName = sb.name,
    onRename = { newName ->
        NodewireNetwork.CHANNEL.sendToServer(
            SetSideBindingNamePacket(
                sourcePos = sourceBe.blockPos,
                sourceChannelName = sb.sourceChannelName,
                targetPos = sb.targetPos,
                targetSide = sb.targetSide,
                name = newName,
            ),
        )
        version++  // re-snapshot
    },
    onRemove = { /* existing */ },
)
```

Channel-binding row stays unchanged (no `onRename`).

- [ ] **Step 3: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "$(cat <<'EOF'
feat(bindings): show block name in header + inline rename for side bindings

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Hand-off smoke

1. Place a logic block. Open editor (RMB without link tool). Toolbar shows empty name input.
2. Type "Hub" → close & reopen editor → name persists.
3. Click "Bindings…" toolbar button → BindingsManagerScreen header shows "Block Hub · …".
4. Bind side to a redstone lamp. In Manager, type "lamp" into the binding's inline name → close & reopen → name persists; row shows "lamp" as primary label.
5. Existing channel-binding rows look the same as before.
