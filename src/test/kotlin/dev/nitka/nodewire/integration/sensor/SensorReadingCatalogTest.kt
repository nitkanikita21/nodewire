package dev.nitka.nodewire.integration.sensor

import dev.nitka.nodewire.graph.PinType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SensorReadingCatalogTest {
    @Test fun `catalog has exactly the ten documented readings`() {
        assertEquals(10, SensorReading.entries.size)
    }

    @Test fun `pin types and needsFilter match the spec table`() {
        fun r(n: String) = SensorReading.fromName(n)!!
        assertEquals(PinType.INT to false, r("ITEM_COUNT").pinType to r("ITEM_COUNT").needsFilter)
        assertEquals(PinType.FLOAT to false, r("ITEM_FILL").pinType to r("ITEM_FILL").needsFilter)
        assertEquals(PinType.INT to true, r("COUNT_OF").pinType to r("COUNT_OF").needsFilter)
        assertEquals(PinType.BOOL to true, r("CONTAINS").pinType to r("CONTAINS").needsFilter)
        assertEquals(PinType.INT to false, r("FLUID_MB").pinType to r("FLUID_MB").needsFilter)
        assertEquals(PinType.FLOAT to false, r("FLUID_FILL").pinType to r("FLUID_FILL").needsFilter)
        assertEquals(PinType.BOOL to true, r("FLUID_IS").pinType to r("FLUID_IS").needsFilter)
        assertEquals(PinType.INT to false, r("COMPARATOR").pinType to r("COMPARATOR").needsFilter)
        assertEquals(PinType.BOOL to false, r("IS_EMPTY").pinType to r("IS_EMPTY").needsFilter)
        assertEquals(PinType.BOOL to false, r("IS_FULL").pinType to r("IS_FULL").needsFilter)
    }

    @Test fun `fromName roundtrip and miss`() {
        for (r in SensorReading.entries) assertEquals(r, SensorReading.fromName(r.name))
        assertNull(SensorReading.fromName("NOPE"))
    }

    @Test fun `every reading has a known pin type`() {
        for (r in SensorReading.entries) assert(r.pinType in PinType.entries)
    }
}
