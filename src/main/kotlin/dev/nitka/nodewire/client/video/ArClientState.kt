package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.systems.RenderSystem
import dev.nitka.nodewire.item.ArGlassesItem
import dev.nitka.nodewire.radio.RadioChannels
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import java.util.UUID

object ArClientState {
    @Volatile
    var activeHandle: UUID = RadioChannels.NIL_HANDLE

    /** Reception signal 0..1 of the active video (carried from the Hub). Drives
     *  the same noise shader the Screen block uses, so radio video degrades on
     *  the AR HUD exactly as it does on a screen. 1 = clean. */
    @Volatile
    var activeSignal: Float = 1f

    private var wasWearingLastTick = false

    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val stack = player.getItemBySlot(EquipmentSlot.HEAD)
        val wearing = stack.item is ArGlassesItem

        if (wearing && activeHandle != RadioChannels.NIL_HANDLE) {
            val w = mc.window.guiScaledWidth
            val h = mc.window.guiScaledHeight
            VideoManager.requestSurfaceSize(activeHandle, w, h)
        }

        if (!wearing) {
            if (wasWearingLastTick) {
                activeHandle = RadioChannels.NIL_HANDLE
            }
        }
        wasWearingLastTick = wearing
    }

    fun onRenderGuiPre(event: RenderGuiEvent.Pre) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val stack = player.getItemBySlot(EquipmentSlot.HEAD)
        val wearing = stack.item is ArGlassesItem
        if (!wearing) return

        RenderSystem.disableDepthTest()
        try {
            if (activeHandle == RadioChannels.NIL_HANDLE) {
                val txt = Component.literal("NO SIGNAL").withStyle(ChatFormatting.RED)
                val w = mc.window.guiScaledWidth
                event.guiGraphics.drawString(
                    mc.font, txt,
                    w / 2 - mc.font.width(txt) / 2,
                    10,
                    0xFFFF0000.toInt(),
                    false,
                )
                return
            }

            val surface = VideoManager.getOrCreate(activeHandle) as? GlVideoSurface ?: return
            val texId = surface.colorTextureId()
            val w = mc.window.guiScaledWidth.toFloat()
            val h = mc.window.guiScaledHeight.toFloat()
            // Full-screen blit of the AR overlay FBO — NO backing: most of this FBO
            // is transparent on purpose (the HUD shows through to the real view), and
            // any per-window dark backing was already composited INTO the FBO by the
            // script's image() verb. A backing here would black out the whole HUD.
            VideoBlit.blit(texId, 0f, 0f, w, h, activeSignal)
        } finally {
            RenderSystem.enableDepthTest()
        }
    }
}
