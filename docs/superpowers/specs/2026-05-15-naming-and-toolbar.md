# Naming & Editor Toolbar — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Goal

Three related additions that improve discoverability and identification of bindings and blocks:

1. **LogicBlock name** — `LogicBlockEntity` gains a user-editable `name: String`. Persists, syncs, shown in the editor toolbar + BindingsManagerScreen header.
2. **SideBinding name** — `SideBinding` data class gains a user-editable `name: String`. Editable inline in BindingsManagerScreen. Defaults to empty; fallback display uses existing channel-name + side-glyph.
3. **Editor toolbar** — Top bar in `NodeEditorScreen` hosting the block-name field, an inline "Bindings…" button, and reserved space for future settings.

## Non-goals

- Channel-binding name (channel bindings already inherit names from source/target channel_output/channel_input nodes; redundant).
- In-world block nameplate above the block.
- Name visible on wire labels (labels removed in earlier spec; not reintroducing).
- Filter / list / search across all logic blocks by name.
- Migration of saved worlds — existing saves load with name="" everywhere (no break).

## Components

### `LogicBlockEntity` — name field

```kotlin
private var name: String = ""
fun getName(): String = name
fun setName(value: String) {
    if (name == value) return
    name = value
    setChanged()
    val l = level ?: return
    l.sendBlockUpdated(blockPos, blockState, blockState, 3) // flags=3 (clients + neighbors)
}
```

**NBT:** in `saveAdditional(tag)` add `tag.putString("name", name)` if non-empty (omit empty to keep tag small). In `load(tag)` read `name = tag.getString("name")` — vanilla returns `""` for missing keys, perfect default.

**`getUpdateTag()` / `handleUpdatePacket(...)`** — verify the existing override path already routes through `saveAdditional`/`load`. If yes, no extra code needed. If not (some BEs only sync a subset), explicitly include `name` in the update tag path.

### `SideBinding` — name field

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

`optionalFieldOf("name", "")` keeps backward-compat for SideBindings already serialized in saved worlds — old NBT entries deserialize with `name=""`.

`LogicBlockEntity.addSideBinding(...)` signature gets an optional `name: String = ""` param. Existing call site (the `BindSideChannelPacket` handler) passes `""`; later flows can pass user-entered names.

A new method `renameSideBinding(sourceChannelName, targetPos, targetSide, newName)` on `LogicBlockEntity`: finds the matching binding by key tuple, replaces it with a copy carrying the new name, marks changed + syncs.

### Packets

**`SetBlockNamePacket(pos: BlockPos, name: String)`** — client→server.
- Validation: `name.length <= 64`. Empty allowed.
- Handler: `level.getBlockEntity(pos) as? LogicBlockEntity`, call `setName(name)`. Silently ignore if BE missing.

**`SetSideBindingNamePacket(sourcePos, sourceChannelName, targetPos, targetSide, name)`** — client→server.
- Validation: same length limit.
- Handler: find source BE, call `renameSideBinding(...)`. Silently ignore if BE/binding missing.

Both registered in `NodewireNetwork.register()` with new message IDs incremented from the existing ones.

### `EditorState` extensions

`EditorState` already wraps an `editor.graph` and `editor.pos`. Add:

```kotlin
private val _blockName = MutableStateFlow("")
val blockName: StateFlow<String> = _blockName.asStateFlow()
fun setBlockName(name: String) { _blockName.value = name } // local only — server sync via packet
```

Initialized from `LogicBlockEntity.getName()` in `NodeEditorScreen.Content()` (`remember(graph) { EditorState(graph, pos).also { ...; it.setBlockName(be.getName()) } }`).

### `EditorToolbar` Composable

New file: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt`.

```kotlin
@Composable
fun EditorToolbar(
    pos: BlockPos,
    onOpenBindings: () -> Unit,
) {
    val editor = LocalEditorState.current ?: return
    val name by editor.blockName.collectAsState()
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
            placeholder = "Logic Block (${pos.x}, ${pos.y}, ${pos.z})",
            onValueChange = { next ->
                editor.setBlockName(next)
                NameDebouncer.schedule(pos, next)
            },
        )
        Box(modifier = Modifier.weight(1f))
        Button(onClick = onOpenBindings) { Text("Bindings…") }
    }
}

private const val NAME_INPUT_WIDTH = 200
```

`NameDebouncer` — small util that coalesces rapid edits into one packet ~300ms after typing stops. Implementation: a `mutableMapOf<BlockPos, Job>` keyed by pos, cancels prior job, schedules a `delay(300); send(...)`. Lives in the same file as `EditorToolbar` for now.

### `NodeEditorScreen` integration

Wrap `Box` content in a `Column` with the toolbar on top:

```kotlin
Column {
    EditorToolbar(pos = pos, onOpenBindings = { mc.setScreen(BindingsManagerScreen(...)) })
    Box(...) { /* existing canvas */ }
}
```

`BindingsManagerScreen` already takes a callback for picking a source — for the "Bindings…" toolbar button, pass an empty callback `{ }` (the screen still works as a viewer/manager even without source-pick flow). Actually: this needs verification — if the screen requires a non-null callback, supply a no-op `{ _ -> }`. The screen's purpose in "viewer" mode is to show + remove bindings.

### `BindingsManagerScreen` updates

1. **Header** — replace the `(${pos.toShortString()})` portion with `block.name.ifBlank { "(${pos.toShortString()})" }`. (Plus the `Link Manager` subtitle stays.)
2. **Side-binding row** — add an inline `TextInput` next to the description showing `name`, with debounced packet on edit. Width ~120px to fit alongside.

Channel-binding rows: untouched.

## Files touched

- `src/main/kotlin/dev/nitka/nodewire/block/LogicBlockEntity.kt`
- `src/main/kotlin/dev/nitka/nodewire/block/SideBinding.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/net/SetBlockNamePacket.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/net/SetSideBindingNamePacket.kt`
- `src/main/kotlin/dev/nitka/nodewire/net/NodewireNetwork.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorToolbar.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt`
- `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`
- Tests: codec roundtrip for SideBinding with non-empty name; packet handler validation cases.

## Tests

- `SideBindingCodecTest` — roundtrip with name="", name="some-label", + decode legacy NBT (no `name` field) and verify result has `name=""`.
- `LogicBlockEntityNameTest` (or extend existing BE test) — save/load with `name="Hub"` survives roundtrip; default is `""`.
- `SetBlockNamePacketTest` — validates length ≤ 64; missing BE → no-op (no exception).
- `SetSideBindingNamePacketTest` — same shape; missing binding → no-op.

UI smoke (hand-off):
- Open editor of a logic block → toolbar shows empty name input with placeholder `Logic Block (x, y, z)`.
- Type "Hub" → after ~300ms (no visible delay), close screen, reopen → name persists.
- Click "Bindings…" toolbar button → BindingsManagerScreen opens. Header shows "Hub".
- Bind a side target. In Manager, edit the side binding's inline name → debounced save → confirmed on reopen.

## Out of scope (deferred)

- Channel-binding names.
- Bulk rename / find-replace.
- Name autocomplete or templating ($x, $y placeholders).
- In-world floating nameplate.
- Searchable list-of-blocks UI.
