package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.Comment
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextArea
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
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
        Column(modifier = Modifier.fillMaxSize()) {
            // Header strip — drag handle + right-click menu.
            Surface(
                modifier = Modifier
                    .size(comment.width, HEADER_HEIGHT)
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
                style = SurfaceStyle(
                    color = NwTheme.colors.surfacePressed,
                    shape = NwTheme.shapes.small,
                    border = null,
                    padding = PaddingValues(
                        horizontal = NwTheme.dimens.space4,
                        vertical = NwTheme.dimens.space2,
                    ),
                ),
            ) {
                Text("Comment", style = NwTheme.typography.caption)
            }

            // Body — view or edit mode.
            // Single left-click on the body enters edit mode (PointerEvent.Press
            // carries no clickCount, so we cannot distinguish single vs double).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NwTheme.dimens.space4)
                    .pointerInput { ev, _, _ ->
                        if (ev is PointerEvent.Press && ev.button == LEFT_BUTTON) {
                            editing = true
                            true
                        } else false
                    },
            ) {
                if (editing) {
                    TextArea(
                        value = comment.text,
                        onValueChange = { editor.updateCommentText(comment.id, it) },
                        modifier = Modifier.fillMaxSize(),
                        placeholder = "type here…",
                    )
                } else {
                    val lines = if (comment.text.isEmpty()) listOf("(empty)") else comment.text.split('\n')
                    Column {
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
