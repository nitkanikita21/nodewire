package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeId
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
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.client.Minecraft
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
            Popup(
                position = PopupPosition.Anchored(a, PopupPlacement.Below, gap = 2),
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
 * Placeholder for the item icon. Task 6 will replace with a real
 * GuiGraphics.renderItem call via a new NwCanvas.drawItem extension.
 * For now: empty stack → nothing, non-empty → coloured square so the slot
 * shows it has content.
 */
@Composable
private fun ItemSlotIcon(stack: ItemStack) {
    if (stack.isEmpty) return
    Box(modifier = Modifier.size(16).background(NwTheme.colors.accent))
}

private fun setSlot(node: Node, editor: EditorState?, slotKey: String, stack: ItemStack) {
    editor?.updateNode(node.id) { n ->
        n.copy(config = n.config.copy().apply {
            put(slotKey, stack.save(CompoundTag()))
        })
    }
}

@Composable
private fun InventoryPickerPopover(onPick: (ItemStack) -> Unit) {
    val inventory = remember {
        Minecraft.getInstance().player?.inventory?.items?.toList().orEmpty()
    }
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
            .border(BorderStroke(1, NwTheme.colors.border))
            .padding(all = 6),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4)) {
            TextInput(
                value = query,
                placeholder = "Search...",
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
            )
            val rows = unique.chunked(9)
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
