package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.client.screen.EditorState
import dev.nitka.nodewire.client.screen.LocalEditorState
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Dialog
import dev.nitka.nodewire.ui.components.DialogContent
import dev.nitka.nodewire.ui.components.Divider
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceDefaults
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.client.screen.NodeCard
import dev.nitka.nodewire.graph.StockNodeTypes
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.ui.components.Tooltip
import dev.nitka.nodewire.ui.canvas.NodeCanvas
import dev.nitka.nodewire.ui.canvas.rememberCanvasState
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.scroll.rememberScrollState
import dev.nitka.nodewire.ui.scroll.verticalScroll
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.network.chat.Component

/**
 * Phase 12 — comprehensive showcase. Visual sanity check across the full
 * component surface: typography ramp, pin-type color swatches, button
 * variants in all states, tooltip with delay.
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.w
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        NwThemeProvider {
            var clicks by remember { mutableStateOf(0) }
            val scroll = rememberScrollState()
            // Outer Column: full-screen background + scroll container, no padding.
            // Inner Column: padding + spacing, sizes naturally vertically.
            // Splitting the concerns means padding/spacing aren't entangled with
            // the scroll viewport's own dimensions.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NwTheme.colors.background)
                    .verticalScroll(scroll),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NwTheme.dimens.space16),
                    verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space12),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text("Nodewire UI", style = NwTheme.typography.title)
                    Text(
                        "Compose-runtime + Yoga + GuiGraphics. Phase 12.",
                        style = NwTheme.typography.body.copy(color = NwTheme.colors.onSurfaceMuted),
                    )
                    Divider()

                    TypographySection()
                    Divider()

                    PinPalette()
                    Divider()

                    ButtonGallery(clicks = clicks, onClick = { clicks++ })
                    Divider()

                    TooltipDemo()
                    Divider()

                    DialogDemo()
                    Divider()

                    NodeCanvasDemo()
                }
            }
        }
    }

    @Composable
    private fun DialogDemo() {
        var open by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Dialog", style = NwTheme.typography.subtitle)
            Button(onClick = { open = true }) { Text("Open dialog") }
        }
        if (open) {
            Dialog(onDismissRequest = { open = false }) {
                DialogContent {
                    Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8)) {
                        Text("Confirm action", style = NwTheme.typography.title)
                        Text(
                            "Click outside the panel or press Cancel to dismiss.",
                            style = NwTheme.typography.body.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8),
                        ) {
                            Button(onClick = { open = false }, style = ButtonDefaults.ghost()) {
                                Text("Cancel")
                            }
                            Button(onClick = { open = false }) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TypographySection() {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Typography", style = NwTheme.typography.subtitle)
            Text("Title", style = NwTheme.typography.title)
            Text("Subtitle", style = NwTheme.typography.subtitle)
            Text("Body — the quick brown fox jumps over the lazy dog.")
            Text(
                "caption text",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }

    @Composable
    private fun PinPalette() {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Pin types", style = NwTheme.typography.subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6)) {
                PinSwatch("bool", NwTheme.colors.pinBool)
                PinSwatch("int", NwTheme.colors.pinInt)
                PinSwatch("float", NwTheme.colors.pinFloat)
                PinSwatch("vec2", NwTheme.colors.pinVec2)
                PinSwatch("vec3", NwTheme.colors.pinVec3)
                PinSwatch("quat", NwTheme.colors.pinQuat)
            }
        }
    }

    @Composable
    private fun PinSwatch(label: String, color: dev.nitka.nodewire.ui.render.Color) {
        Column(
            horizontalAlignment = Alignment.Center,
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
        ) {
            Box(modifier = Modifier.size(20).background(color))
            Text(
                label,
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }

    @Composable
    private fun ButtonGallery(clicks: Int, onClick: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Buttons", style = NwTheme.typography.subtitle)
            Text(
                "Clicked $clicks times.",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8)) {
                Button(onClick = onClick) { Text("Filled") }
                Button(onClick = onClick, style = ButtonDefaults.outlined()) { Text("Outlined") }
                Button(onClick = onClick, style = ButtonDefaults.ghost()) { Text("Ghost") }
                Button(onClick = onClick, style = ButtonDefaults.danger()) { Text("Danger") }
                Button(onClick = onClick, enabled = false) { Text("Disabled") }
            }
        }
    }

    @Composable
    private fun NodeCanvasDemo() {
        val canvas = rememberCanvasState()
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Node canvas (middle-drag to pan, wheel to zoom)", style = NwTheme.typography.subtitle)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(400, 220),
            ) {
                // Build a small demo graph so NodeCard can read from per-node
                // flows via LocalEditorState. The graph is stable for the
                // lifetime of this screen (remember with no keys).
                val demoEditor = remember {
                    val g = NodeGraph()
                    listOf(
                        StockNodeTypes.CONSTANT.newInstance(CanvasPos(0f, 0f)),
                        StockNodeTypes.CONSTANT.newInstance(CanvasPos(160f, 0f)).also {
                            it.config.putString("type", "INT")
                        },
                        StockNodeTypes.CONSTANT.newInstance(CanvasPos(320f, 0f)).also {
                            it.config.putString("type", "STRING")
                        },
                        StockNodeTypes.ADD_INT.newInstance(CanvasPos(0f, 90f)),
                        StockNodeTypes.COMPARE_INT.newInstance(CanvasPos(160f, 90f)),
                        StockNodeTypes.AND.newInstance(CanvasPos(320f, 90f)),
                    ).forEach { g.add(it) }
                    EditorState(g)
                }
                val demoNodeIds by demoEditor.nodes.collectAsState()
                NodeCanvas(state = canvas, modifier = Modifier.fillMaxSize()) {
                    // Real NodeCards built from registered StockNodeTypes —
                    // verifies pin alignment on borders, type labels, and
                    // the config-section hook (CONSTANT renders a type selector + value field).
                    CompositionLocalProvider(LocalEditorState provides demoEditor) {
                        for (id in demoNodeIds) {
                            NodeCard(nodeId = id)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DemoNodeCard(worldX: Int, worldY: Int, label: String, accent: dev.nitka.nodewire.ui.render.Color) {
        Surface(
            modifier = Modifier.absolutePosition(worldX, worldY).size(96, 48),
            style = SurfaceDefaults.outlined(),
        ) {
            Column(
                modifier = Modifier.padding(NwTheme.dimens.space6),
                verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
            ) {
                Text(label, style = NwTheme.typography.caption)
                Box(modifier = Modifier.size(10).background(accent))
            }
        }
    }

    @Composable
    private fun TooltipDemo() {
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
            Text("Tooltip (hover for 400ms)", style = NwTheme.typography.subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space8)) {
                Tooltip(text = "Filled button — primary action") {
                    Button(onClick = {}) { Text("Hover me") }
                }
                Tooltip(text = "Outlined — secondary") {
                    Surface(style = SurfaceDefaults.outlined()) {
                        Box(modifier = Modifier.size(80, 24).padding(NwTheme.dimens.space4)) {
                            Text("Outlined surface")
                        }
                    }
                }
            }
        }
    }
}
