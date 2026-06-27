package dev.nitka.nodewire.link

import com.mojang.serialization.Codec
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

/**
 * The third pin-link host: a per-[ServerLevel] home for [HostlessLink]s, whose
 * consumer is a foreign block with no BE of ours to store them. Augments — does
 * not replace — the two existing hosts (consumer-hosted [PinLinkSink] pull and
 * the redstone source-push), so working links keep their block-local
 * persistence and "break the block → link gone" behaviour.
 *
 * Lives on the PARENT level: Sable sub-levels are plot regions inside the
 * parent, so one store per dimension covers both plain and sub-level endpoints.
 */
class HostlessLinkStore : SavedData() {

    private val links = mutableListOf<HostlessLink>()

    /** Add [link], replacing any existing entry with the same
     *  [HostlessLink.identity] (re-bind of the same pos/pin pair). */
    fun add(link: HostlessLink) {
        links.removeAll { it.identity == link.identity }
        links.add(link)
        setDirty()
    }

    /** Remove the link matching [identity]. Returns true when one was found. */
    fun remove(identity: HostlessLink.Identity): Boolean {
        val removed = links.removeAll { it.identity == identity }
        if (removed) setDirty()
        return removed
    }

    /** Snapshot-safe read of the stored links. Mutate only via [add]/[remove]
     *  (or, for the latch/prune, the delivery engine). */
    fun links(): List<HostlessLink> = links.toList()

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.put(
            KEY_LINKS,
            CODEC_LIST.encodeStart(NbtOps.INSTANCE, links).result().orElse(ListTag()),
        )
        return tag
    }

    companion object {
        private const val ID = "nodewire_hostless_links"
        private const val KEY_LINKS = "links"
        private val CODEC_LIST: Codec<List<HostlessLink>> = HostlessLink.CODEC.listOf()

        private val FACTORY = SavedData.Factory(::HostlessLinkStore, ::load)

        /** The store for [level] (the parent level), creating it if absent. */
        fun of(level: ServerLevel): HostlessLinkStore =
            level.dataStorage.computeIfAbsent(FACTORY, ID)

        /** Rebuild a store from its saved tag (used by [FACTORY]). */
        internal fun load(tag: CompoundTag, @Suppress("UNUSED_PARAMETER") registries: HolderLookup.Provider): HostlessLinkStore {
            val store = HostlessLinkStore()
            CODEC_LIST.parse(NbtOps.INSTANCE, tag.get(KEY_LINKS) ?: ListTag())
                .result().ifPresent { store.links.addAll(it) }
            return store
        }
    }
}
