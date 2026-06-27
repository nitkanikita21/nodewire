package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HostlessLinkStoreTest {

    @BeforeEach fun resetBackends() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(WorldBackend)
    }

    private fun ref(x: Int, y: Int, z: Int) =
        EndpointRef(WorldBackend.id, WorldPayload(BlockPos(x, y, z)))

    @Test fun `link codec round-trip preserves endpoints and pins`() {
        val link = HostlessLink(ref(1, 2, 3), "out", ref(4, 5, 6), "target_pitch")
        val tag = HostlessLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().orElseThrow()
        val decoded = HostlessLink.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(link, decoded)
    }

    @Test fun `store save-load round-trip preserves links`() {
        val store = HostlessLinkStore()
        store.add(HostlessLink(ref(1, 2, 3), "out", ref(4, 5, 6), "target_pitch"))
        store.add(HostlessLink(ref(7, 8, 9), "signal", ref(4, 5, 6), "target_yaw"))

        // EndpointRef.CODEC ignores the registries, so an empty access is fine.
        val provider = RegistryAccess.EMPTY
        val saved = store.save(CompoundTag(), provider)
        val loaded = HostlessLinkStore.load(saved, provider)

        assertEquals(store.links(), loaded.links())
    }

    @Test fun `add replaces by identity`() {
        val store = HostlessLinkStore()
        store.add(HostlessLink(ref(1, 2, 3), "out", ref(4, 5, 6), "target_pitch"))
        // Same source-pos / src-pin / target-pos / tgt-pin → replace, not duplicate.
        store.add(HostlessLink(ref(1, 2, 3), "out", ref(4, 5, 6), "target_pitch"))
        assertEquals(1, store.links().size)
    }

    @Test fun `remove by identity drops the matching link`() {
        val store = HostlessLinkStore()
        val link = HostlessLink(ref(1, 2, 3), "out", ref(4, 5, 6), "target_pitch")
        store.add(link)
        assertTrue(store.remove(link.identity))
        assertTrue(store.links().isEmpty())
        assertFalse(store.remove(link.identity))
    }
}
