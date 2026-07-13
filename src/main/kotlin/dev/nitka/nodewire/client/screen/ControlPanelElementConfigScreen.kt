package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.panel.PanelElementConfig
import dev.nitka.nodewire.net.ConfigureElementPacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.scroll.rememberScrollState
import dev.nitka.nodewire.ui.scroll.verticalScroll
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Config editor for a single Control Panel element (Panel Key, sneak-RMB). One
 * text input per [PanelElementConfig] field; commits the whole config tag on
 * close via [ConfigureElementPacket].
 */
class ControlPanelElementConfigScreen(
    private val pos: BlockPos,
    private val cellX: Int,
    private val cellY: Int,
    private val typeId: String,
    initial: CompoundTag,
) : NwComposeScreen(Component.literal("Panel Element")) {

    private val schema = PanelElementConfig.fields(typeId)
    private var values: Map<String, String> by mutableStateOf(
        schema.associate { it.key to PanelElementConfig.read(initial, it) },
    )

    override fun onClose() {
        PacketDistributor.sendToServer(
            ConfigureElementPacket(pos, cellX, cellY, PanelElementConfig.build(typeId, values)),
        )
        super.onClose()
    }

    private fun set(key: String, v: String) {
        values = values + (key to v)
    }

    @Composable
    override fun Content() {
        NwThemeProvider {
            Box(modifier = Modifier.fillMaxSize().padding(NwTheme.dimens.space8)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    style = SurfaceStyle(
                        color = NwTheme.colors.surface,
                        shape = NwTheme.shapes.medium,
                        border = BorderStroke(1, NwTheme.colors.border),
                        padding = PaddingValues(NwTheme.dimens.space8),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Center,
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Text("Configure $typeId", style = NwTheme.typography.subtitle)
                            Box(modifier = Modifier.weight(1f)) {}
                            Button(onClick = { onClose() }) { Text("Close") }
                        }

                        if (schema.isEmpty()) {
                            Text(
                                "(this element has no options)",
                                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                            )
                        } else {
                            val scroll = rememberScrollState()
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
                                verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                            ) {
                                schema.forEach { f -> FieldRow(f) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FieldRow(f: PanelElementConfig.Field) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(f.label, style = NwTheme.typography.caption, modifier = Modifier.width(120))
            TextInput(
                value = values[f.key] ?: f.default,
                onValueChange = { set(f.key, it) },
                modifier = Modifier.width(120),
                placeholder = f.default,
            )
        }
    }

    companion object {
        /** Open the config editor for the element at ([cellX], [cellY]) on the
         *  panel at [pos] (client). */
        fun open(pos: BlockPos, cellX: Int, cellY: Int, typeId: String, cfg: CompoundTag) {
            Minecraft.getInstance().setScreen(ControlPanelElementConfigScreen(pos, cellX, cellY, typeId, cfg))
        }
    }
}
