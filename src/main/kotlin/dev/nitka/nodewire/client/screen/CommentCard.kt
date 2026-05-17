package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Comment
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextArea
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Floating plain-text annotation. View mode renders the text split on
 * newlines (each line as its own [Text]); clicking the body switches to
 * edit mode using [TextArea] (no double-click support — [PointerEvent.Press]
 * carries no click count). Drag the top strip to move, drag the
 * bottom-right corner to resize. Right-click opens the comment context menu
 * (Delete).
 */
@Composable
fun CommentCard(comment: Comment) {
    val editor = LocalEditorState.current ?: return
    val canvas = LocalCanvasState.current
    var editing by remember(comment.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .absolutePosition(comment.pos.x.toInt(), comment.pos.y.toInt())
            .size(comment.width, comment.height)
            .background(NwTheme.colors.surfaceHover, NwTheme.shapes.medium)
            .border(BorderStroke(1, NwTheme.colors.border), NwTheme.shapes.medium),
    ) {
        // Header strip — drag handle + right-click menu.
        // Plain Box (not Surface) so the click area matches the visual size
        // exactly: no SurfaceStyle padding inflating it.
        Box(
            modifier = Modifier
                .width(comment.width)
                .height(HEADER_HEIGHT)
                .background(NwTheme.colors.surfacePressed)
                .padding(horizontal = NwTheme.dimens.space4)
                .pointerInput { ev, x, y ->
                    when (ev) {
                        is PointerEvent.Drag -> {
                            val zoom = canvas?.zoom ?: 1f
                            editor.moveComment(comment.id, ev.deltaX / zoom, ev.deltaY / zoom)
                            true
                        }
                        is PointerEvent.Press -> {
                            if (ev.button == RIGHT_BUTTON && canvas != null) {
                                val worldX = comment.pos.x + x
                                val worldY = comment.pos.y + y
                                val sx = ((worldX + canvas.panX) * canvas.zoom).toInt()
                                val sy = ((worldY + canvas.panY) * canvas.zoom).toInt()
                                editor.openCommentMenu(sx, sy, comment.id)
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Text("Comment", style = NwTheme.typography.caption)
        }

        // Body — view or edit mode. Sized explicitly to the remaining
        // height so TextArea / Text never overflows the card.
        val bodyW = comment.width
        val bodyH = (comment.height - HEADER_HEIGHT).coerceAtLeast(0)
        Box(
            modifier = Modifier
                .absolutePosition(0, HEADER_HEIGHT)
                .size(bodyW, bodyH)
                .padding(NwTheme.dimens.space4)
                .pointerInput { ev, _, _ ->
                    if (ev is PointerEvent.Press && ev.button == LEFT_BUTTON) {
                        editing = true
                        true
                    } else false
                },
        ) {
            val innerW = (bodyW - NwTheme.dimens.space4 * 2).coerceAtLeast(0)
            val innerH = (bodyH - NwTheme.dimens.space4 * 2).coerceAtLeast(0)
            if (editing) {
                TextArea(
                    value = comment.text,
                    onValueChange = { editor.updateCommentText(comment.id, it) },
                    modifier = Modifier.size(innerW, innerH),
                    placeholder = "type here…",
                )
            } else {
                val lines = if (comment.text.isEmpty()) listOf("(empty)") else comment.text.split('\n')
                Column(modifier = Modifier.size(innerW, innerH)) {
                    for (line in lines) {
                        Text(
                            line,
                            style = if (comment.text.isEmpty())
                                NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted)
                            else NwTheme.typography.caption,
                        )
                    }
                }
            }
        }

        // Resize handle (bottom-right corner).
        Box(
            modifier = Modifier
                .absolutePosition(comment.width - RESIZE_HANDLE, comment.height - RESIZE_HANDLE)
                .size(RESIZE_HANDLE, RESIZE_HANDLE)
                .background(NwTheme.colors.border)
                .pointerInput { ev, _, _ ->
                    when (ev) {
                        is PointerEvent.Drag -> {
                            val zoom = canvas?.zoom ?: 1f
                            editor.resizeComment(
                                comment.id,
                                (comment.width + (ev.deltaX / zoom).toInt()),
                                (comment.height + (ev.deltaY / zoom).toInt()),
                            )
                            true
                        }
                        is PointerEvent.Press -> true
                        else -> false
                    }
                },
        )
    }
}

private const val HEADER_HEIGHT = 12
private const val RESIZE_HANDLE = 8
private const val LEFT_BUTTON = 0
private const val RIGHT_BUTTON = 1
