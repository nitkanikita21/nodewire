package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.AbstractApplier

/**
 * Translates Compose composition mutations into [UiNode] tree mutations,
 * keeping the parallel Yoga tree in sync with each operation.
 *
 * Yoga's `removeChild(int)` does *not* clear the removed child's owner, so a
 * subsequent `addChildAt` of the same node would throw. We use
 * `removeChildAndInvalidate(YogaNode)` for every detach — it nulls the owner
 * and marks the tree dirty.
 */
internal class NwApplier(root: UiNode) : AbstractApplier<UiNode>(root) {

    override fun onClear() {
        root.children.forEach { root.yoga.removeChildAndInvalidate(it.yoga); it.parent = null }
        root.children.clear()
    }

    override fun insertTopDown(index: Int, instance: UiNode) {
        // No-op. AbstractApplier dispatches to insertBottomUp afterwards.
    }

    override fun insertBottomUp(index: Int, instance: UiNode) {
        current.children.add(index, instance)
        current.yoga.addChildAt(instance.yoga, index)
        instance.parent = current
    }

    override fun remove(index: Int, count: Int) {
        repeat(count) {
            val child = current.children.removeAt(index)
            current.yoga.removeChildAndInvalidate(child.yoga)
            child.parent = null
        }
    }

    override fun move(from: Int, to: Int, count: Int) {
        if (from == to) return
        val moved = ArrayList<UiNode>(count).apply {
            repeat(count) { add(current.children[from + it]) }
        }

        // Detach (clears yoga owner via removeChildAndInvalidate).
        repeat(count) {
            current.children.removeAt(from)
            current.yoga.removeChildAndInvalidate(moved[it].yoga)
        }

        // After detach, the destination index is `to - count` when moving forward.
        val insertAt = if (from < to) to - count else to
        moved.forEachIndexed { i, node ->
            current.children.add(insertAt + i, node)
            current.yoga.addChildAt(node.yoga, insertAt + i)
        }
    }
}
