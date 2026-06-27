package dev.nitka.nodewire.integration.sable

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.link.HostlessLink
import dev.nitka.nodewire.link.HostlessLinkStore
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEvent
import dev.rew1nd.sableschematicapi.compat.BlueprintRefTags
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation

/**
 * Makes the level-hosted [HostlessLink]s survive a Sable schematic copy-paste —
 * the blueprint-level parallel to the per-BE [SablePinLinkMapper].
 *
 * A host-less link has no BlockEntity to ride: it lives in the parent level's
 * [HostlessLinkStore], with BOTH ends as Sable sub-level [EndpointRef]s whose
 * `blockPos` is the ABSOLUTE plot coordinate in the parent level. A pasted copy
 * lands in NEW sub-levels at DIFFERENT plot regions, so every end's sub-level
 * UUID AND plot position must be remapped — otherwise a rebuilt link points at
 * the ORIGINAL structure's blocks. Unlike the per-BE mapper, there is no block
 * tag to piggy-back on, so this works at the blueprint level via the event hook:
 *  * [onSaveAfterBlocks] — once blocks are recorded ([BlueprintSaveSession.blockRef]
 *    resolves), scan the source store and capture each link whose BOTH ends fall
 *    inside the copied region as a position-independent record (per-end
 *    [BlueprintBlockRef][dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef]
 *    + original sub-level UUID), stashed under [LINKS_KEY] in the event's
 *    blueprint-local data (which the registry round-trips through
 *    [BlueprintSaveSession.globalExtraData]). Links with an end outside the copy
 *    get no ref → dropped.
 *  * [onPlaceAfterBlocks] — read those records, remap each end via
 *    [BlueprintPlaceSession.mapBlock] + [BlueprintPlaceSession.mapSubLevel], and
 *    add the rebuilt links to the destination level's [HostlessLinkStore].
 */
object SableHostlessLinkMapper : SableBlueprintEvent {

    private val LOG = LogUtils.getLogger()
    private const val LINKS_KEY = "links"

    private val ID = ResourceLocation.fromNamespaceAndPath("nodewire", "hostless_links")

    override fun id(): ResourceLocation = ID

    /** SAVE: turn each in-region host-less link into a position-independent record. */
    override fun onSaveAfterBlocks(session: BlueprintSaveSession, data: CompoundTag) {
        val links = HostlessLinkStore.of(session.level()).links()
        if (links.isEmpty()) return

        val records = ListTag()
        for (link in links) {
            val src = link.source.payload as? SableSubLevelPayload ?: continue   // non-sub-level end → drop
            val tgt = link.target.payload as? SableSubLevelPayload ?: continue
            val srcRef = session.blockRef(src.blockPos).orElse(null) ?: continue  // source outside copy → drop
            val tgtRef = session.blockRef(tgt.blockPos).orElse(null) ?: continue  // target outside copy → drop
            val rec = CompoundTag()
            HostlessLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().ifPresent { rec.put("link", it) }
            rec.putUUID("src_sub", src.subLevelId)
            rec.put("src_ref", BlueprintRefTags.write(srcRef))
            rec.putUUID("tgt_sub", tgt.subLevelId)
            rec.put("tgt_ref", BlueprintRefTags.write(tgtRef))
            records.add(rec)
        }
        if (!records.isEmpty()) data.put(LINKS_KEY, records)
    }

    /** PLACE: rebuild each link with remapped sub-level UUIDs + plot positions. */
    override fun onPlaceAfterBlocks(session: BlueprintPlaceSession, data: CompoundTag) {
        val records = data.getList(LINKS_KEY, Tag.TAG_COMPOUND.toInt())
        if (records.isEmpty()) return

        val store = HostlessLinkStore.of(session.level())
        for (t in records) {
            val rec = t as? CompoundTag ?: continue
            val link = decodeLink(rec.get("link")) ?: continue
            val srcRef = BlueprintRefTags.read(rec, "src_ref").orElse(null) ?: continue
            val tgtRef = BlueprintRefTags.read(rec, "tgt_ref").orElse(null) ?: continue
            val origSrcSub = rec.getUUID("src_sub")
            val origTgtSub = rec.getUUID("tgt_sub")

            val newSrcPos = session.mapBlock(srcRef)
            val newSrcSub = session.mapSubLevel(origSrcSub)
            val newTgtPos = session.mapBlock(tgtRef)
            val newTgtSub = session.mapSubLevel(origTgtSub)
            if (newSrcPos == null || newSrcSub == null || newTgtPos == null || newTgtSub == null) {
                LOG.info(
                    "[NW-SCHEM] drop host-less link: unmapped end (srcPos={}, srcSub={}, tgtPos={}, tgtSub={})",
                    newSrcPos, newSrcSub, newTgtPos, newTgtSub,
                )
                continue
            }

            val rebuilt = link.copy(
                source = EndpointRef(link.source.backendId, SableSubLevelPayload(newSrcSub, newSrcPos)),
                target = EndpointRef(link.target.backendId, SableSubLevelPayload(newTgtSub, newTgtPos)),
            )
            store.add(rebuilt)
            LOG.info(
                "[NW-SCHEM] remap host-less link -> src sub {} pos {} / tgt sub {} pos {}",
                newSrcSub, newSrcPos.toShortString(), newTgtSub, newTgtPos.toShortString(),
            )
        }
    }

    private fun decodeLink(t: Tag?): HostlessLink? =
        HostlessLink.CODEC.parse(NbtOps.INSTANCE, t).result().orElse(null)
}
