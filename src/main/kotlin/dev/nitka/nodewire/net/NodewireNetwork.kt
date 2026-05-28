package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * Registers every Nodewire packet with NeoForge's PayloadRegistrar.
 * Fires on the mod event bus during [RegisterPayloadHandlersEvent].
 */
@EventBusSubscriber(modid = Nodewire.ID, bus = EventBusSubscriber.Bus.MOD)
object NodewireNetwork {

    // No @JvmStatic — KFF's AutoKotlinEventBusSubscriber registers Kotlin
    // `object`s as instances, so @SubscribeEvent methods must be instance
    // methods (Kotlin auto-routes them through INSTANCE).
    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1")
        // Client → server packets
        registrar.playToServer(SaveGraphPacket.TYPE, SaveGraphPacket.STREAM_CODEC, SaveGraphPacket::handle)
        registrar.playToServer(BindChannelPacket.TYPE, BindChannelPacket.STREAM_CODEC, BindChannelPacket::handle)
        registrar.playToServer(BindAeroSourcePacket.TYPE, BindAeroSourcePacket.STREAM_CODEC, BindAeroSourcePacket::handle)
        registrar.playToServer(BindSideChannelPacket.TYPE, BindSideChannelPacket.STREAM_CODEC, BindSideChannelPacket::handle)
        registrar.playToServer(BindRemoteRedstonePacket.TYPE, BindRemoteRedstonePacket.STREAM_CODEC, BindRemoteRedstonePacket::handle)
        registrar.playToServer(RemoveBindingPacket.TYPE, RemoveBindingPacket.STREAM_CODEC, RemoveBindingPacket::handle)
        registrar.playToServer(SetBlockNamePacket.TYPE, SetBlockNamePacket.STREAM_CODEC, SetBlockNamePacket::handle)
        registrar.playToServer(SetSideBindingNamePacket.TYPE, SetSideBindingNamePacket.STREAM_CODEC, SetSideBindingNamePacket::handle)
        // Server → client packets
        registrar.playToClient(HighlightPacket.TYPE, HighlightPacket.STREAM_CODEC, HighlightPacket::handle)
    }
}
