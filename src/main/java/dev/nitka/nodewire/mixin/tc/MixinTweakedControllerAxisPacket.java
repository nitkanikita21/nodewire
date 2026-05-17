package dev.nitka.nodewire.mixin.tc;

import com.getitemfromblock.create_tweaked_controllers.block.TweakedLecternControllerBlockEntity;
import com.getitemfromblock.create_tweaked_controllers.packet.TweakedLinkedControllerAxisPacket;
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
 * Mirror of {@link MixinTweakedControllerButtonPacket} for axis packets.
 * Captures both the packed int (low-precision mode) and the
 * {@code float[] fullAxis} (high-precision mode); the receiver picks
 * whichever is populated.
 */
@Pseudo
@Mixin(value = TweakedLinkedControllerAxisPacket.class, remap = false)
public abstract class MixinTweakedControllerAxisPacket {

    @Shadow
    private int axis;

    @Shadow
    private float[] fullAxis;

    @Inject(method = "handleItem", at = @At("RETURN"), remap = false)
    private void nodewire$onHandleItem(ServerPlayer player, ItemStack heldItem, CallbackInfo ci) {
        BlockPos hub = ControllerHubItem.INSTANCE.getHub(heldItem);
        if (hub == null) return;
        ControllerStatePipeline.pushAxes(player.level(), hub, this.axis, this.fullAxis);
    }

    @Inject(method = "handleLectern", at = @At("RETURN"), remap = false)
    private void nodewire$onHandleLectern(ServerPlayer player, TweakedLecternControllerBlockEntity lectern, CallbackInfo ci) {
        ItemStack stack = lectern.getController();
        if (stack == null || stack.isEmpty()) return;
        BlockPos hub = ControllerHubItem.INSTANCE.getHub(stack);
        if (hub == null) return;
        ControllerStatePipeline.pushAxes(player.level(), hub, this.axis, this.fullAxis);
    }
}
