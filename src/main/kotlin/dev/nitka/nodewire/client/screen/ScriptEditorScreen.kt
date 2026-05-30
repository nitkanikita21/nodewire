package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.net.SetScriptSourcePacket
import dev.nitka.nodewire.script.ScriptDiagnostics
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextArea
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
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import dev.nitka.nodewire.script.lexer.ScriptHighlighter
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Full-screen source editor for a `script` node. Opened from the script
 * node's `configContent` "📜 Edit" button. Holds local edit state; the
 * authoritative source stays in the server-side node config.
 *
 * Layout: a title bar (node display name), a large highlighted [TextArea]
 * bound to local source state, a bottom status strip fed by the node's
 * synced diagnostics ([ScriptDiagnostics]), and a Close button.
 *
 * Apply-on-close: both ESC and the Close button trigger [closeAndApply].
 * If the text changed since open, a [SetScriptSourcePacket] commits it to the
 * server (which re-reshapes pins + prunes edges); then we return to the node
 * editor for [pos]. No explicit Apply button — per the design spec.
 */
class ScriptEditorScreen(
    private val pos: BlockPos,
    private val nodeId: NodeId,
    private val initialSrc: String,
    private val nodeName: String,
) : NwComposeScreen(Component.literal("Script — $nodeName")) {

    private var src: String = initialSrc

    /**
     * Read the live (re-synced) diagnostics token + text from the client BE's
     * copy of this node's config. The server stamps these after (re)compile;
     * the client BE graph is kept in sync, so polling per frame shows the
     * last known status without any client compile.
     */
    private fun diagnostics(): Pair<String, String> {
        val level = Minecraft.getInstance().level ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
            ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        val node = be.graph.nodes[nodeId] ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        return ScriptDiagnostics.readStatus(node.config) to ScriptDiagnostics.readText(node.config)
    }

    /** Commit edits (if changed) + return to the node editor. */
    private fun closeAndApply() {
        val mc = Minecraft.getInstance()
        val be = mc.level?.getBlockEntity(pos) as? LogicBlockEntity
        if (src != initialSrc) {
            PacketDistributor.sendToServer(SetScriptSourcePacket(pos, nodeId, src))
            // Mirror the edit into the CLIENT graph immediately. The server
            // round-trip hasn't landed yet, and the node editor we re-open saves
            // the WHOLE graph on close (NodeEditorScreen.removed → SaveGraphPacket).
            // Without this the stale client graph would clobber the just-sent src
            // (this also reshapes pins + prunes edges client-side to match).
            be?.let { LogicBlockEntity.applyScriptSourceToGraph(it.graph, nodeId, src) }
        }
        mc.setScreen(if (be != null) NodeEditorScreen(pos, be.graph) else null)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Let the focused TextArea consume first (typing, its own ESC = blur).
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            closeAndApply()
            return true
        }
        return false
    }

    @Composable
    override fun Content() {
        NwThemeProvider {
            var text by remember { mutableStateOf(src) }
            val (statusToken, statusText) = diagnostics()
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
                        // Title bar — node display name.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Center,
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Text(nodeName, style = NwTheme.typography.subtitle)
                            Box(modifier = Modifier.weight(1f)) {}
                            Button(onClick = { closeAndApply() }) { Text("Close") }
                        }

                        // Source editor — fills the remaining height.
                        TextArea(
                            value = text,
                            onValueChange = { new -> text = new; src = new },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            highlight = { line -> ScriptHighlighter.highlight(line) },
                        )

                        // Status strip — last known server diagnostics.
                        StatusStrip(statusToken, statusText)
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusStrip(token: String, text: String) {
        val (label, color) = when (token) {
            ScriptDiagnostics.STATUS_COMPILING ->
                "Compiling…" to NwTheme.colors.onSurfaceMuted
            ScriptDiagnostics.STATUS_OK ->
                "✅ Compiled" to NwTheme.colors.onSurfaceMuted
            ScriptDiagnostics.STATUS_ERROR ->
                "❌ ${text.ifEmpty { "Compile error" }}" to NwTheme.colors.pinRedstone
            else -> "" to NwTheme.colors.onSurfaceMuted
        }
        if (label.isEmpty()) return
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = NwTheme.typography.caption.copy(color = color))
        }
    }
}
