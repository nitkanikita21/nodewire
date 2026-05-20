package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.graph.PinType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AeroChannelCatalogTest {

    @Test fun `every block kind has at least one channel`() {
        for (kind in AeroBlockKind.entries) {
            val channels = AeroChannel.byKind(kind)
            assertTrue(channels.isNotEmpty(), "no channels for $kind")
        }
    }

    @Test fun `every channel has a known pin type`() {
        for (ch in AeroChannel.entries) {
            assertTrue(ch.pinType in PinType.entries, "unknown PinType on $ch")
        }
    }

    @Test fun `fromName roundtrip`() {
        for (ch in AeroChannel.entries) {
            assertEquals(ch, AeroChannel.fromName(ch.name))
        }
        assertNull(AeroChannel.fromName("DOES_NOT_EXIST"))
    }

    @Test fun `AeroBlockKind fromName roundtrip`() {
        for (k in AeroBlockKind.entries) {
            assertEquals(k, AeroBlockKind.fromName(k.name))
        }
        assertNull(AeroBlockKind.fromName("NOT_A_KIND"))
    }

    @Test fun `byKind partitions all channels`() {
        val total = AeroBlockKind.entries.sumOf { AeroChannel.byKind(it).size }
        assertEquals(AeroChannel.entries.size, total, "byKind union should equal full catalog")
    }
}
