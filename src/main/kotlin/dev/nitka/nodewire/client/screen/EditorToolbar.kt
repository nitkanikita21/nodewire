package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
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
            placeholder = "Logic Block (${pos.x}, ${pos.y}, ${pos.z})",
            onValueChange = { next ->
                editor.setBlockName(next)
                debouncer.schedule(pos, next)
            },
        )
        val tcLoaded = dev.nitka.nodewire.integration.tweakedcontroller.TweakedController.isLoaded()
        if (!tcLoaded) {
            Text(
                "Controller: (TC not loaded)",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        } else {
            // The binding lives on the controller ITEM (Drive-By-Wire model)
            // not on the block — so the block has no "bound id" to display.
            // Hint the player: hold a controller and RMB to link.
            Text(
                "Controller: hold a TC controller and RMB the block",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
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
