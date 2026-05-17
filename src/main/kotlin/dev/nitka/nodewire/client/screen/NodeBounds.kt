package dev.nitka.nodewire.client.screen

/**
 * Last-known layout size of a node card on the canvas. Published by
 * [NodeCard.onPositioned] and consumed by frame/fit operations on
 * [EditorState]. Width/height are pixels in canvas-world space (i.e.
 * the post-yoga layout size; multiply by canvas zoom for screen px).
 */
data class NodeBounds(val width: Int, val height: Int)
