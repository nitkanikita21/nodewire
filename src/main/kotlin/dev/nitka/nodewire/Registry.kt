package dev.nitka.nodewire

import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.block.LogicBlock
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.block.ScreenBlock
import dev.nitka.nodewire.block.ScreenBlockEntity
import dev.nitka.nodewire.item.ChannelLinkToolItem
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

object Registry {
    private val BLOCKS: DeferredRegister.Blocks =
        DeferredRegister.createBlocks(Nodewire.ID)
    private val ITEMS: DeferredRegister.Items =
        DeferredRegister.createItems(Nodewire.ID)
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Nodewire.ID)

    val LOGIC_BLOCK: DeferredBlock<LogicBlock> = BLOCKS.register("logic_block") { _ ->
        LogicBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val LOGIC_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(LOGIC_BLOCK)

    val CHANNEL_LINK_TOOL: DeferredItem<ChannelLinkToolItem> = ITEMS.register("channel_link_tool") { _ ->
        ChannelLinkToolItem(Item.Properties().stacksTo(1))
    }

    val SCREEN_BLOCK: DeferredBlock<ScreenBlock> = BLOCKS.register("screen_block") { _ ->
        ScreenBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val SCREEN_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(SCREEN_BLOCK)

    val CAMERA_BLOCK: DeferredBlock<CameraBlock> = BLOCKS.register("camera_block") { _ ->
        CameraBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val CAMERA_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(CAMERA_BLOCK)

    val LOGIC_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<LogicBlockEntity>> =
        BLOCK_ENTITIES.register("logic_block") { _ ->
            BlockEntityType.Builder
                .of(::LogicBlockEntity, LOGIC_BLOCK.get())
                .build(null)
        }

    val SCREEN_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<ScreenBlockEntity>> =
        BLOCK_ENTITIES.register("screen_block") { _ ->
            BlockEntityType.Builder
                .of(::ScreenBlockEntity, SCREEN_BLOCK.get())
                .build(null)
        }

    val CAMERA_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<CameraBlockEntity>> =
        BLOCK_ENTITIES.register("camera_block") { _ ->
            BlockEntityType.Builder
                .of(::CameraBlockEntity, CAMERA_BLOCK.get())
                .build(null)
        }

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
        bus.addListener(::onBuildTabs)
        // Link Tool needs no per-block registration any more: Screen/Camera/
        // Logic implement PinPort directly; foreign blocks resolve through
        // the PinPorts adapters (aero / sensor / redstone fallback).
    }

    private fun onBuildTabs(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(LOGIC_BLOCK_ITEM.get())
            event.accept(SCREEN_BLOCK_ITEM.get())
            event.accept(CAMERA_BLOCK_ITEM.get())
        }
        if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CHANNEL_LINK_TOOL.get())
        }
    }
}
