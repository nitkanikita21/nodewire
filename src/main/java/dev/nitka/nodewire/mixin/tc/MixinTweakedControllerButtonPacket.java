package dev.nitka.nodewire.mixin.tc;

import com.getitemfromblock.create_tweaked_controllers.block.TweakedLecternControllerBlockEntity;
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
 * Hooks Tweaked Controller's button packet at its server-side handlers.
 * After TC has done its own processing (the `@At("RETURN")` site), we
 * read the raw button bitmask via {@code @Shadow} and forward it to the
 * Nodewire Logic Block whose {@link BlockPos} the bound controller
 * stack carries in NBT (set by {@link ControllerHubItem#putHub}).
 *
 * <p>{@code @Pseudo} marks the mixin as optional — the target class
 * only exists when Create: Tweaked Controllers is present. Without TC,
 * the mixin silently skips and Nodewire still loads.
 *
 * <p>{@code remap = false} because TC's class/method/field names are
 * already Mojang-mapped at compile time (and not in vanilla MC mappings).
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
        ControllerStatePipeline.pushButtons(player.level(), hub, this.buttonStates);
    }

    @Inject(method = "handleLectern", at = @At("RETURN"), remap = false)
    private void nodewire$onHandleLectern(ServerPlayer player, TweakedLecternControllerBlockEntity lectern, CallbackInfo ci) {
        ItemStack stack = lectern.getController();
        if (stack == null || stack.isEmpty()) return;
        BlockPos hub = ControllerHubItem.INSTANCE.getHub(stack);
        if (hub == null) return;
        ControllerStatePipeline.pushButtons(player.level(), hub, this.buttonStates);
    }
}
