package dev.nitka.nodewire.client.camera

import net.minecraft.client.renderer.SectionOcclusionGraph

/** Marker subclass: each feed owns a distinct occlusion graph so its visibility
 *  BFS runs against ITS OWN storage and never touches the player's graph → no
 *  render-distance-edge flicker. Vista's `FeedSectionOcclusionGraph`. */
class FeedSectionOcclusionGraph : SectionOcclusionGraph()
