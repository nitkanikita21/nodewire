package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.GroupBbox
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.width

/**
 * Inline rename popup for the group currently in `editor.renamingGroup`.
 * Lives inside the NodeCanvas so it pans/zooms with the world — sits in
 * the same header strip GroupFrame paints (one GROUP_HEADER_HEIGHT above
 * the bbox top). Transparent so the existing group name stays visible
 * while editing; autofocused so typing works immediately.
 *
 * Bbox is recomputed from current member positions every recomposition,
 * mirroring [GroupFrame] — so a moving group's overlay tracks the header.
 */
@Composable
fun GroupLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val groupId = editor.renamingGroup ?: return
    val allGroups by editor.groups.collectAsState()
    val group = allGroups.firstOrNull { it.id == groupId } ?: return
    val allGroupsById = remember(allGroups) { allGroups.associateBy { it.id } }
    val closure = remember(group, allGroupsById) {
        GroupProxyPins.memberClosure(group, allGroupsById)
    }
    val rects = closure.mapNotNull { id ->
        val n = editor.nodeFlow(id)?.collectAsState()?.value ?: return@mapNotNull null
        val sz = editor.cardSize(id) ?: (200 to 60)
        n.pos to sz
    }
    val bbox = GroupBbox.compute(group.pos, rects)
    // Match GroupFrame's header positioning: pad above bbox, then the
    // header strip starts at bbox.minY - pad - GROUP_HEADER_HEIGHT. We
    // place the input a couple pixels inside that strip.
    val pad = dev.nitka.nodewire.ui.theme.NwTheme.dimens.space8
    val x = (bbox.minX - pad).toInt() + 4
    val y = (bbox.minY - pad - GROUP_HEADER_HEIGHT).toInt() + 2
    val w = (bbox.maxX - bbox.minX).toInt() + pad * 2 - 8
    var text by remember(groupId, group.name) { mutableStateOf(group.name) }
    Box(
        modifier = Modifier
            .absolutePosition(x, y)
            .width(w),
    ) {
        TextInput(
            value = text,
            placeholder = "name",
            transparent = true,
            autoFocus = true,
            onValueChange = { text = it },
            onSubmit = {
                editor.setGroupName(groupId, text)
                editor.renamingGroup = null
            },
            // Escape discards; click-away (focus lost) commits.
            onCancel = { editor.renamingGroup = null },
            onFocusLost = {
                editor.setGroupName(groupId, text)
                editor.renamingGroup = null
            },
        )
    }
}
