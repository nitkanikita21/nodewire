package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.ui.components.Checkbox
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Per-NodeType configuration widgets. The factories below are wired into
 * [NodeType.configContent] from `StockNodeTypes` so each node type can
 * decide what config UI (if any) appears inside its card.
 *
 * Pattern: each composable owns a `remember`d state primed from
 * [Node.config], with the change handler writing back to `node.config`
 * AND updating local state so Compose re-renders. The config tag is the
 * source of truth on save; local state is only for in-session reactivity.
 */
object NodeConfigContent {

    /** INT_CONST: numeric text input. Non-numeric chars cause the value to stay at last valid; emptied → 0. */
    val IntConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("value").toString()) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "0",
            onValueChange = { new ->
                // Accept "", "-", and any prefix of an int. Parse and write
                // through whatever's numerically valid; the text shown stays
                // exactly what the user typed (so they can type "-" then "3").
                val filtered = new.filterIndexed { i, c ->
                    c.isDigit() || (c == '-' && i == 0)
                }
                text = filtered
                val v = filtered.toIntOrNull() ?: 0
                node.config.putInt("value", v)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** FLOAT_CONST: numeric text input accepting optional sign and one dot. */
    val FloatConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getFloat("value").toString()) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "0.0",
            onValueChange = { new ->
                // Accept digits, optional leading '-', and at most one '.'.
                var dotSeen = false
                val filtered = buildString {
                    for ((i, c) in new.withIndex()) {
                        when {
                            c.isDigit() -> append(c)
                            c == '-' && i == 0 -> append(c)
                            c == '.' && !dotSeen -> { append(c); dotSeen = true }
                        }
                    }
                }
                text = filtered
                val v = filtered.toFloatOrNull() ?: 0f
                node.config.putFloat("value", v)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** STRING_CONST: plain text input, no filtering. */
    val StringConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getString("value")) }
        TextInput(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            placeholder = "(empty)",
            onValueChange = { new ->
                text = new
                node.config.putString("value", new)
                editor?.bumpGraphVersion()
            },
        )
    }

    /** DELAY: number of ticks the input is held back by. */
    val DelayTicks: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("delay").toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(
                "Ticks",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceIn(0, 200)
                    node.config.putInt("delay", v)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    /** TIMER: integer period in ticks. Same widget shape as IntConst. */
    val TimerPeriod: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var text by remember { mutableStateOf(node.config.getInt("period").toString()) }
        Row(
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Text(
                "Period",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
            TextInput(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }
                    text = filtered
                    val v = (filtered.toIntOrNull() ?: 0).coerceAtLeast(1)
                    node.config.putInt("period", v)
                    editor?.bumpGraphVersion()
                },
            )
        }
    }

    /** BOOL_CONST: single checkbox bound to `config.value`. */
    val BoolConst: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current
        var value by remember { mutableStateOf(node.config.getBoolean("value")) }
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
        ) {
            Checkbox(
                checked = value,
                onCheckedChange = { v ->
                    value = v
                    node.config.putBoolean("value", v)
                    editor?.bumpGraphVersion()
                },
            )
            Text(
                if (value) "true" else "false",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }
}
