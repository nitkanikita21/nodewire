package dev.nitka.nodewire.client.panel

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.neoforged.neoforge.client.event.ModelEvent

/**
 * Preloaded standalone model for Control Panel elements — a direct port of
 * Dashpanels' `PreLoadedModel` (MIT, BoxxedDev; see THIRD_PARTY_LICENSES.md):
 *
 *  * models register via [ModelEvent.RegisterAdditional] and are snapshotted
 *    once per bake in [ModelEvent.BakingCompleted] — no per-frame lookups;
 *  * chunk render types remap to their ENTITY sheet variants (their "flat
 *    lighting fix": the chunk types ignore vertex normals in a BER);
 *  * quads are emitted via `putBulkData`, which applies the tint colour to
 *    EVERY quad — so untinted Blockbench models can still be dyed (the bulb).
 */
class PanelModel private constructor(private val location: ResourceLocation) {

    private var model: BakedModel? = null

    fun render(pose: PoseStack, buffers: MultiBufferSource, renderType: RenderType, packedLight: Int, colorPacked: Int = 0xFFFFFF) {
        val baked = model ?: return // not baked (yet) / missing — skip, never the missing-cube
        val red = Mth.clamp(((colorPacked shr 16) and 0xFF) / 255f, 0f, 1f)
        val green = Mth.clamp(((colorPacked shr 8) and 0xFF) / 255f, 0f, 1f)
        val blue = Mth.clamp((colorPacked and 0xFF) / 255f, 0f, 1f)

        val sheet = when (renderType) {
            RenderType.solid() -> Sheets.solidBlockSheet()
            RenderType.translucent() -> Sheets.translucentCullBlockSheet()
            RenderType.cutout(), RenderType.cutoutMipped() -> Sheets.cutoutBlockSheet()
            else -> renderType
        }
        val consumer = buffers.getBuffer(sheet)
        val random = RandomSource.create()
        val last = pose.last()
        for (direction in net.minecraft.core.Direction.entries) {
            random.setSeed(42L)
            for (quad in baked.getQuads(null, direction, random)) {
                consumer.putBulkData(last, quad, red, green, blue, 1f, packedLight, OverlayTexture.NO_OVERLAY)
            }
        }
        random.setSeed(42L)
        for (quad in baked.getQuads(null, null, random)) {
            consumer.putBulkData(last, quad, red, green, blue, 1f, packedLight, OverlayTexture.NO_OVERLAY)
        }
    }

    companion object {
        private val ALL = LinkedHashMap<ResourceLocation, PanelModel>()

        private fun create(path: String): PanelModel {
            val loc = ResourceLocation.fromNamespaceAndPath("nodewire", "panel/$path")
            return ALL.getOrPut(loc) { PanelModel(loc) }
        }

        // ── the catalog (Dashpanels-derived assets under models/panel/) ──
        val PLATE = create("panel_plate")
        val SWITCH_ON = create("switch_on")
        val SWITCH_OFF = create("switch_off")
        val MOMENTARY_BASE = create("momentary_base")
        val MOMENTARY_BUTTON = create("momentary_button")
        val BULB_BASE = create("bulb_base")
        val BULB_ON = create("bulb_on")
        val BULB_OFF = create("bulb_off")
        val KNOB = create("knob")
        val LABEL = create("label")
        val SEVEN_SEGMENT = create("seven_segment")

        /** MOD bus: register every model as a standalone additional model. */
        fun registerAdditional(event: ModelEvent.RegisterAdditional) {
            for (loc in ALL.keys) event.register(ModelResourceLocation.standalone(loc))
        }

        /** MOD bus: snapshot the baked instances (Dashpanels' bake hook). */
        fun bakingCompleted(event: ModelEvent.BakingCompleted) {
            for ((loc, pm) in ALL) {
                pm.model = event.models[ModelResourceLocation.standalone(loc)]
            }
        }
    }
}
