package dev.nitka.nodewire.mixin.tc;

import com.getitemfromblock.create_tweaked_controllers.block.TweakedLecternControllerBlockEntity;
import com.getitemfromblock.create_tweaked_controllers.controller.ControllerRedstoneOutput;
import com.getitemfromblock.create_tweaked_controllers.packet.TweakedLinkedControllerButtonPacket;
import dev.nitka.nodewire.integration.tweakedcontroller.ControllerHubItem;
import dev.nitka.nodewire.integration.tweakedcontroller.ControllerStatePipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixes into TC's server-side button packet handlers and forwards the
 * decoded button bitmask to the Nodewire Logic Block whose {@link BlockPos}
 * the bound controller stack carries in NBT (set by
 * {@link ControllerHubItem#putHub(ItemStack, BlockPos)}).
 *
 * <p>{@code @Pseudo} marks the target as optional — without TC on the
 * runtime classpath the mixin silently skips and Nodewire still loads.
 * Decoding goes through TC's own {@code ControllerRedstoneOutput} so the
 * wire-format bit layout never has to be guessed.
 */
@Pseudo
@Mixin(value = TweakedLinkedControllerButtonPacket.class, remap = false)
public abstract class MixinTweakedControllerButtonPacket {

    @Shadow
    private short buttonStates;

    @Inject(method = "handleItem", at = @At("RETURN"), remap = false)
    private void nodewire$onHandleItem(ServerPlayer player, ItemStack heldItem, CallbackInfo ci) {
        BlockPos hub = ControllerHubItem.INSTANCE.getHub(heldItem);
        if (hub == null) return;
        ControllerRedstoneOutput out = new ControllerRedstoneOutput();
        out.DecodeButtons(this.buttonStates);
        ControllerStatePipeline.pushButtonStates(player.level(), hub, out.buttons);
    }

    @Inject(method = "handleLectern", at = @At("RETURN"), remap = false)
    private void nodewire$onHandleLectern(ServerPlayer player, TweakedLecternControllerBlockEntity lectern, CallbackInfo ci) {
        ItemStack stack = lectern.getController();
        if (stack == null || stack.isEmpty()) return;
        BlockPos hub = ControllerHubItem.INSTANCE.getHub(stack);
        if (hub == null) return;
        ControllerRedstoneOutput out = new ControllerRedstoneOutput();
        out.DecodeButtons(this.buttonStates);
        ControllerStatePipeline.pushButtonStates(player.level(), hub, out.buttons);
    }
}
