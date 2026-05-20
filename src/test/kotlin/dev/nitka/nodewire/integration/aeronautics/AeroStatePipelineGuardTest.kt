package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.graph.NodeGraph
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AeroStatePipelineGuardTest {

    @Test fun `snapshot returns empty when aeronautics is not loaded`() {
        val result = AeroStatePipeline.snapshot(
            level = null,
            graph = NodeGraph(),
            aeronauticsLoaded = false,
        )
        assertTrue(result.isEmpty(), "snapshot should be empty under guard, got $result")
    }

    @Test fun `snapshot returns empty for an empty graph when loaded`() {
        val result = AeroStatePipeline.snapshot(
            level = null,
            graph = NodeGraph(),
            aeronauticsLoaded = true,
        )
        assertTrue(result.isEmpty(), "empty graph snapshot should be empty")
    }
}
