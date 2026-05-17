package dev.nitka.nodewire.block

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Validates the encoding contract used by [LogicBlockEntity.saveAdditional]
 * for the `controllerId` field: present iff non-null, decodes via
 * [CompoundTag.hasUUID] / [CompoundTag.getUUID]. We don't instantiate
 * the BE itself (requires MC bootstrap); the helper mirrors the
 * save/load logic for the bound-id slot exclusively.
 */
class LogicBlockEntityControllerBindTest {

    private fun save(tag: CompoundTag, id: UUID?) {
        id?.let { tag.putUUID("controllerId", it) }
    }

    private fun load(tag: CompoundTag): UUID? =
        if (tag.hasUUID("controllerId")) tag.getUUID("controllerId") else null

    @Test fun roundTripWithId() {
        val id = UUID.randomUUID()
        val tag = CompoundTag()
        save(tag, id)
        assertEquals(id, load(tag))
    }

    @Test fun roundTripNullOmitsKey() {
        val tag = CompoundTag()
        save(tag, null)
        assertNull(load(tag))
        // also: nothing was written
        assert(!tag.hasUUID("controllerId"))
    }
}
