package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.ui.canvas.CanvasState
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.clickable
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * Two ghost slots labelled "Frequency" for a redstone_link_input/output node.
 * Each slot:
 *   - Shows current ItemStack (placeholder square until Task 6 adds icon render).
 *   - LMB → opens an inline player-inventory popover.
 *   - RMB → clears to ItemStack.EMPTY.
 *
 * Slot screen rects are published into [RedstoneLinkSlotRegistry] every
 * frame so JEI/EMI ghost-drag handlers (later tasks) can hit-test against them.
 */
@Composable
fun RedstoneLinkFrequencySlots(node: Node, editor: EditorState?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        verticalAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4),
    ) {
        Text("Frequency")
        FrequencySlot(node, editor, slotKey = "freq1")
        FrequencySlot(node, editor, slotKey = "freq2")
    }
}

@Composable
private fun FrequencySlot(node: Node, editor: EditorState?, slotKey: String) {
    var pickerOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val canvas = LocalCanvasState.current
    val currentStack = ItemStack.of(node.config.getCompound(slotKey))

    Box(
        modifier = Modifier
            .size(18)
            .background(NwTheme.colors.surface)
            .border(BorderStroke(1, NwTheme.colors.border))
            .onPositioned { coords ->
                anchor = coords
                RedstoneLinkSlotRegistry.update(node.id, slotKey, coords) { stack ->
                    setSlot(node, editor, slotKey, stack)
                }
            }
            .clickable { pickerOpen = true }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press && ev.button == 1) {
                    setSlot(node, editor, slotKey, ItemStack.EMPTY)
                    true
                } else false
            },
    ) {
        ItemSlotIcon(currentStack)
    }

    if (pickerOpen) {
        val a = anchor
        if (a != null) {
            val (px, py) = popoverScreenAnchor(a, canvas)
            Popup(
                position = PopupPosition.AtScreen(px, py),
                dismissOnClickOutside = true,
                onDismissRequest = { pickerOpen = false },
            ) {
                InventoryPickerPopover(
                    onPick = { picked ->
                        setSlot(node, editor, slotKey, picked)
                        pickerOpen = false
                    },
                )
            }
        }
    }
}

/**
 * Compute the true screen-pixel position to anchor the picker popover below
 * the slot. `onPositioned` reports coords accumulated through the layout
 * walk WITHOUT applying the surrounding NodeCanvas pose (pan + zoom); for
 * canvas-nested widgets we have to invert that ourselves so the popover
 * lands directly under the slot regardless of how the user has panned or
 * zoomed the editor.
 */
private fun popoverScreenAnchor(anchor: LayoutCoordinates, canvas: CanvasState?): Pair<Int, Int> {
    if (canvas == null) {
        return anchor.screenX to (anchor.screenY + anchor.height + 2)
    }
    val z = canvas.zoom
    val localX = anchor.screenX - canvas.originX
    val localY = anchor.screenY - canvas.originY
    val realX = canvas.originX + ((localX + canvas.panX) * z).toInt()
    val realY = canvas.originY + ((localY + canvas.panY) * z).toInt()
    return realX to (realY + (anchor.height * z).toInt() + 2)
}

@Composable
private fun ItemSlotIcon(stack: ItemStack) {
    if (stack.isEmpty) return
    dev.nitka.nodewire.ui.components.ItemIcon(stack)
}

private fun setSlot(node: Node, editor: EditorState?, slotKey: String, stack: ItemStack) {
    editor?.updateNode(node.id) { n ->
        n.copy(config = n.config.copy().apply {
            put(slotKey, stack.save(CompoundTag()))
        })
    }
}

private enum class PickerSource { Inventory, All }

@Composable
private fun InventoryPickerPopover(onPick: (ItemStack) -> Unit) {
    var source by remember { mutableStateOf(PickerSource.Inventory) }
    var query by remember { mutableStateOf("") }

    val candidates = remember(source, query) {
        when (source) {
            PickerSource.Inventory -> uniqueFromInventory(query)
            PickerSource.All -> uniqueFromRegistry(query)
        }
    }

    Box(
        modifier = Modifier
            .background(NwTheme.colors.surface)
            .border(BorderStroke(1, NwTheme.colors.border))
            .padding(all = 6),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4),
                verticalAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextInput(
                    value = query,
                    placeholder = "Search...",
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        source = if (source == PickerSource.Inventory) PickerSource.All else PickerSource.Inventory
                    },
                    style = ButtonDefaults.outlined(),
                ) {
                    Text(if (source == PickerSource.Inventory) "Inv" else "All")
                }
            }
            val rows = candidates.chunked(9)
            for (row in rows.take(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2)) {
                    for (stack in row) {
                        val pickStack = stack.copy().also { it.count = 1 }
                        Box(
                            modifier = Modifier
                                .size(18)
                                .background(NwTheme.colors.surface)
                                .border(BorderStroke(1, NwTheme.colors.border))
                                .clickable { onPick(pickStack) },
                        ) {
                            ItemSlotIcon(stack)
                        }
                    }
                }
            }
        }
    }
}

/** Player inventory, deduped by item id, filtered by query. */
private fun uniqueFromInventory(query: String): List<ItemStack> {
    val items = Minecraft.getInstance().player?.inventory?.items.orEmpty()
    val seen = HashSet<String>()
    return items.filter {
        if (it.isEmpty) return@filter false
        if (!seen.add(it.item.descriptionId)) return@filter false
        query.isEmpty() || it.hoverName.string.contains(query, ignoreCase = true)
    }
}

/**
 * Every registered item in the game, lazily iterated and capped at 36 hits
 * so we don't materialise tens of thousands of stacks just to render a 9×4
 * grid. Empty `query` returns the first 36 items in registration order;
 * non-empty filters by display-name substring.
 */
private fun uniqueFromRegistry(query: String): List<ItemStack> {
    val result = ArrayList<ItemStack>(36)
    for (item in BuiltInRegistries.ITEM) {
        if (item == net.minecraft.world.item.Items.AIR) continue
        val stack = ItemStack(item)
        if (query.isEmpty() || stack.hoverName.string.contains(query, ignoreCase = true)) {
            result.add(stack)
            if (result.size >= 36) break
        }
    }
    return result
}

/**
 * Per-frame registry of active frequency slot screen rects. JEI/EMI plugins
 * iterate [all] to map a dragged ItemStack to a slot under the cursor. Each
 * [update] overwrites the entry for (nodeId, slotKey) so stale rects don't
 * accumulate when a node is moved around the canvas.
 */
internal object RedstoneLinkSlotRegistry {
    data class Slot(
        val screenX: Int,
        val screenY: Int,
        val width: Int,
        val height: Int,
        val accept: (ItemStack) -> Unit,
    )

    private val slots = mutableMapOf<Pair<NodeId, String>, Slot>()

    fun update(nodeId: NodeId, slotKey: String, coords: LayoutCoordinates, accept: (ItemStack) -> Unit) {
        slots[nodeId to slotKey] = Slot(coords.screenX, coords.screenY, coords.width, coords.height, accept)
    }

    fun all(): Collection<Slot> = slots.values

    fun clear() = slots.clear()
}
