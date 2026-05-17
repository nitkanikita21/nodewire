package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.NodeId

/**
 * What the editor's context menu is currently open for. Two flavours:
 *
 *   * [Create] — opened by right-clicking empty canvas. Carries the world
 *     position of the click so the spawned node lands where the user
 *     right-clicked, not at the viewport centre.
 *   * [Node] — opened by right-clicking a node's title bar. Carries the
 *     target node id for action handlers.
 *
 * Both carry the screen-space click position so the popup can anchor at
 * exactly the cursor.
 */
sealed interface ContextMenuTarget {
    val screenX: Int
    val screenY: Int

    data class Create(
        override val screenX: Int,
        override val screenY: Int,
        val world: CanvasPos,
    ) : ContextMenuTarget

    data class Node(
        override val screenX: Int,
        override val screenY: Int,
        val nodeId: NodeId,
    ) : ContextMenuTarget

    data class Group(
        override val screenX: Int,
        override val screenY: Int,
        val groupId: dev.nitka.nodewire.graph.GroupId,
    ) : ContextMenuTarget

    data class Comment(
        override val screenX: Int,
        override val screenY: Int,
        val commentId: dev.nitka.nodewire.graph.CommentId,
    ) : ContextMenuTarget
}
