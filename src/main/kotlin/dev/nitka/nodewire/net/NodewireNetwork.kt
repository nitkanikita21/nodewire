package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel

/**
 * One SimpleChannel for all Nodewire packets. Protocol version is a bare
 * string — mismatched client / server simply refuses to connect, which is
 * what we want until we hit a v2 packet format.
 *
 * Packets are registered in [register], called once at mod init from
 * [Nodewire.init].
 */
object NodewireNetwork {
    private const val PROTOCOL = "1"

    val CHANNEL: SimpleChannel = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation(Nodewire.ID, "main"))
        .networkProtocolVersion { PROTOCOL }
        .clientAcceptedVersions { it == PROTOCOL }
        .serverAcceptedVersions { it == PROTOCOL }
        .simpleChannel()

    fun register() {
        var id = 0
        CHANNEL.messageBuilder(SaveGraphPacket::class.java, id++)
            .encoder(SaveGraphPacket::encode)
            .decoder(SaveGraphPacket::decode)
            .consumerMainThread(SaveGraphPacket::handle)
            .add()
        CHANNEL.messageBuilder(BindChannelPacket::class.java, id++)
            .encoder(BindChannelPacket::encode)
            .decoder(BindChannelPacket::decode)
            .consumerMainThread(BindChannelPacket::handle)
            .add()
        CHANNEL.messageBuilder(BindSideChannelPacket::class.java, id++)
            .encoder(BindSideChannelPacket::encode)
            .decoder(BindSideChannelPacket::decode)
            .consumerMainThread(BindSideChannelPacket::handle)
            .add()
        CHANNEL.messageBuilder(RemoveBindingPacket::class.java, id++)
            .encoder(RemoveBindingPacket::encode)
            .decoder(RemoveBindingPacket::decode)
            .consumerMainThread(RemoveBindingPacket::handle)
            .add()
        CHANNEL.messageBuilder(SetBlockNamePacket::class.java, id++)
            .encoder(SetBlockNamePacket::encode)
            .decoder(SetBlockNamePacket::decode)
            .consumerMainThread(SetBlockNamePacket::handle)
            .add()
        CHANNEL.messageBuilder(SetSideBindingNamePacket::class.java, id++)
            .encoder(SetSideBindingNamePacket::encode)
            .decoder(SetSideBindingNamePacket::decode)
            .consumerMainThread(SetSideBindingNamePacket::handle)
            .add()
        CHANNEL.messageBuilder(HighlightPacket::class.java, id++)
            .encoder(HighlightPacket::encode)
            .decoder(HighlightPacket::decode)
            .consumerMainThread(HighlightPacket::handle)
            .add()
    }
}
