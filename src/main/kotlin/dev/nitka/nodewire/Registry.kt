package dev.nitka.nodewire

import dev.nitka.nodewire.block.LogicBlock
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object Registry {
    private val BLOCKS: DeferredRegister<Block> =
        DeferredRegister.create(ForgeRegistries.BLOCKS, Nodewire.ID)
    private val ITEMS: DeferredRegister<Item> =
        DeferredRegister.create(ForgeRegistries.ITEMS, Nodewire.ID)
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Nodewire.ID)

    val LOGIC_BLOCK: RegistryObject<LogicBlock> = BLOCKS.register("logic_block") {
        LogicBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK))
    }

    val LOGIC_BLOCK_ITEM: RegistryObject<Item> = ITEMS.register("logic_block") {
        BlockItem(LOGIC_BLOCK.get(), Item.Properties())
    }

    // `BlockEntityType.Builder.of(..., LOGIC_BLOCK.get())` is safe in the
    // factory lambda — DeferredRegister calls the lambda after blocks are
    // registered, so `.get()` is valid by then.
    val LOGIC_BLOCK_BE: RegistryObject<BlockEntityType<LogicBlockEntity>> =
        BLOCK_ENTITIES.register("logic_block") {
            BlockEntityType.Builder
                .of(::LogicBlockEntity, LOGIC_BLOCK.get())
                .build(null)
        }

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
        bus.addListener(::onBuildTabs)
    }

    private fun onBuildTabs(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(LOGIC_BLOCK_ITEM.get())
        }
    }
}
