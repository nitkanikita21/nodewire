package dev.nitka.nodewire

import dev.nitka.nodewire.block.ArHubBlock
import dev.nitka.nodewire.block.ArHubBlockEntity
import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.block.LogicBlock
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.block.ControlBlock
import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.ScreenBlock
import dev.nitka.nodewire.block.ScreenBlockEntity
import dev.nitka.nodewire.block.TelemetryBlock
import dev.nitka.nodewire.block.TelemetryBlockEntity
import dev.nitka.nodewire.item.ArGlassesItem
import dev.nitka.nodewire.item.ChannelLinkToolItem
import dev.nitka.nodewire.item.PanelElementItem
import dev.nitka.nodewire.item.PanelKeyItem
import dev.nitka.nodewire.radio.RadioAntennaItem
import dev.nitka.nodewire.radio.RadioReceiverBlock
import dev.nitka.nodewire.radio.RadioReceiverBlockEntity
import dev.nitka.nodewire.radio.RadioTransmitterBlock
import dev.nitka.nodewire.radio.RadioTransmitterBlockEntity
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
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
    private val CREATIVE_TABS: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Nodewire.ID)
    private val SOUNDS: DeferredRegister<net.minecraft.sounds.SoundEvent> =
        DeferredRegister.create(Registries.SOUND_EVENT, Nodewire.ID)

    /** Buzzer element one-shot (Dashpanels' buzz.ogg, MIT). */
    val BUZZ: DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> =
        SOUNDS.register("buzz") { rl: net.minecraft.resources.ResourceLocation ->
            net.minecraft.sounds.SoundEvent.createVariableRangeEvent(rl)
        }

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
        // noOcclusion: the camera isn't a full opaque cube (only a 4-tall base +
        // a BER-rendered gimbal in the airspace above). Without it the block's
        // own cell light is 0 and the moving parts render pitch black.
        CameraBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion())
    }

    val CAMERA_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(CAMERA_BLOCK)

    /** Fixed Camera — aims along its facing only (no gimbal / rotation pins). */
    val STATIC_CAMERA_BLOCK: DeferredBlock<CameraBlock> = BLOCKS.register("static_camera_block") { _ ->
        CameraBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion(), rotatable = false)
    }

    val STATIC_CAMERA_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(STATIC_CAMERA_BLOCK)

    val TELEMETRY_BLOCK: DeferredBlock<TelemetryBlock> = BLOCKS.register("telemetry_block") { _ ->
        TelemetryBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val TELEMETRY_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(TELEMETRY_BLOCK)

    val CONTROL_BLOCK: DeferredBlock<ControlBlock> = BLOCKS.register("control_block") { _ ->
        ControlBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val CONTROL_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(CONTROL_BLOCK)

    /** Thin, no-collision instrument panel — a 16×16 grid of placed controls
     *  and indicators, each exposed as a pin. noCollission: entities pass
     *  through; noOcclusion: the plate + elements are BER-drawn, not a cube. */
    val CONTROL_PANEL: DeferredBlock<ControlPanelBlock> = BLOCKS.register("control_panel") { _ ->
        ControlPanelBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion().noCollission())
    }

    val CONTROL_PANEL_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(CONTROL_PANEL)

    /** One placeable item per element-catalog type, registered `panel_<id>`. */
    val PANEL_ELEMENT_ITEMS: Map<String, DeferredItem<PanelElementItem>> =
        dev.nitka.nodewire.block.panel.PanelElements.ALL.associate { type ->
            type.id to ITEMS.register("panel_${type.id}") { _ -> PanelElementItem(Item.Properties(), type.id) }
        }

    /** Service tool: remove (RMB) / configure (sneak-RMB) panel elements. */
    val PANEL_KEY: DeferredItem<PanelKeyItem> = ITEMS.register("panel_key") { _ ->
        PanelKeyItem(Item.Properties().stacksTo(1))
    }

    val RADIO_TRANSMITTER_BLOCK: DeferredBlock<RadioTransmitterBlock> = BLOCKS.register("radio_transmitter") { _ ->
        RadioTransmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val RADIO_TRANSMITTER_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(RADIO_TRANSMITTER_BLOCK)

    val RADIO_RECEIVER_BLOCK: DeferredBlock<RadioReceiverBlock> = BLOCKS.register("radio_receiver") { _ ->
        RadioReceiverBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val RADIO_RECEIVER_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(RADIO_RECEIVER_BLOCK)

    /** Basic antenna — medium omnidirectional range. */
    val ANTENNA_BASIC: DeferredItem<RadioAntennaItem> = ITEMS.register("antenna_basic") { _ ->
        RadioAntennaItem(Item.Properties().stacksTo(1), range = 512.0, gain = 1.0)
    }

    /** Long-range antenna — wider reach + higher gain (wins the "strongest" contest). */
    val ANTENNA_LONG: DeferredItem<RadioAntennaItem> = ITEMS.register("antenna_long") { _ ->
        RadioAntennaItem(Item.Properties().stacksTo(1), range = 2048.0, gain = 4.0)
    }

    /** Quantum antenna — bridges across dimensions (both ends need one) and huge
     *  in-dimension reach. The cross-world "ansible" tier. */
    val ANTENNA_QUANTUM: DeferredItem<RadioAntennaItem> = ITEMS.register("antenna_quantum") { _ ->
        RadioAntennaItem(Item.Properties().stacksTo(1), range = 8192.0, gain = 8.0, crossWorld = true)
    }

    // ── AR Glasses & Hub ──────────────────────────────────────────────────

    val AR_HUB_BLOCK: DeferredBlock<ArHubBlock> = BLOCKS.register("ar_hub_block") { _ ->
        ArHubBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
    }

    val AR_HUB_BLOCK_ITEM: DeferredItem<BlockItem> =
        ITEMS.registerSimpleBlockItem(AR_HUB_BLOCK)

    val AR_GLASSES_ITEM: DeferredItem<ArGlassesItem> = ITEMS.register("ar_glasses") { _ ->
        ArGlassesItem(Item.Properties().stacksTo(1))
    }

    // ── Creative Tab ──────────────────────────────────────────────────────

    /** Dedicated creative tab holding every Nodewire item (blocks first, then
     *  tools / wearables). Icon is the Logic Block — the system's centrepiece. */
    val NODEWIRE_TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> =
        CREATIVE_TABS.register("nodewire") { _ ->
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.nodewire"))
                .icon { ItemStack(LOGIC_BLOCK_ITEM.get()) }
                .displayItems { _, output ->
                    output.accept(LOGIC_BLOCK_ITEM.get())
                    output.accept(SCREEN_BLOCK_ITEM.get())
                    output.accept(CAMERA_BLOCK_ITEM.get())
                    output.accept(STATIC_CAMERA_BLOCK_ITEM.get())
                    output.accept(TELEMETRY_BLOCK_ITEM.get())
                    output.accept(CONTROL_BLOCK_ITEM.get())
                    output.accept(CONTROL_PANEL_ITEM.get())
                    PANEL_ELEMENT_ITEMS.values.forEach { output.accept(it.get()) }
                    output.accept(PANEL_KEY.get())
                    output.accept(RADIO_TRANSMITTER_BLOCK_ITEM.get())
                    output.accept(RADIO_RECEIVER_BLOCK_ITEM.get())
                    output.accept(AR_HUB_BLOCK_ITEM.get())
                    output.accept(CHANNEL_LINK_TOOL.get())
                    output.accept(ANTENNA_BASIC.get())
                    output.accept(ANTENNA_LONG.get())
                    output.accept(ANTENNA_QUANTUM.get())
                    output.accept(AR_GLASSES_ITEM.get())
                }
                .build()
        }

    // ── Block Entities ────────────────────────────────────────────────────

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
                .of(::CameraBlockEntity, CAMERA_BLOCK.get(), STATIC_CAMERA_BLOCK.get())
                .build(null)
        }

    val TELEMETRY_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<TelemetryBlockEntity>> =
        BLOCK_ENTITIES.register("telemetry_block") { _ ->
            BlockEntityType.Builder
                .of(::TelemetryBlockEntity, TELEMETRY_BLOCK.get())
                .build(null)
        }

    val CONTROL_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<ControlBlockEntity>> =
        BLOCK_ENTITIES.register("control_block") { _ ->
            BlockEntityType.Builder
                .of(::ControlBlockEntity, CONTROL_BLOCK.get())
                .build(null)
        }

    val CONTROL_PANEL_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<ControlPanelBlockEntity>> =
        BLOCK_ENTITIES.register("control_panel") { _ ->
            BlockEntityType.Builder
                .of(::ControlPanelBlockEntity, CONTROL_PANEL.get())
                .build(null)
        }

    val RADIO_TRANSMITTER_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<RadioTransmitterBlockEntity>> =
        BLOCK_ENTITIES.register("radio_transmitter") { _ ->
            BlockEntityType.Builder
                .of(::RadioTransmitterBlockEntity, RADIO_TRANSMITTER_BLOCK.get())
                .build(null)
        }

    val RADIO_RECEIVER_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<RadioReceiverBlockEntity>> =
        BLOCK_ENTITIES.register("radio_receiver") { _ ->
            BlockEntityType.Builder
                .of(::RadioReceiverBlockEntity, RADIO_RECEIVER_BLOCK.get())
                .build(null)
        }

    val AR_HUB_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<ArHubBlockEntity>> =
        BLOCK_ENTITIES.register("ar_hub_block") { _ ->
            BlockEntityType.Builder
                .of(::ArHubBlockEntity, AR_HUB_BLOCK.get())
                .build(null)
        }

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
        CREATIVE_TABS.register(bus)
        SOUNDS.register(bus)
        // Link Tool needs no per-block registration any more: Screen/Camera/
        // Logic implement PinPort directly; foreign blocks resolve through
        // the PinPorts adapters (aero / sensor / redstone fallback).
    }
}
