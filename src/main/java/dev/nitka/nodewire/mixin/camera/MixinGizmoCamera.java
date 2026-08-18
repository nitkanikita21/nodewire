package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.CameraGizmoSession;
import dev.nitka.nodewire.client.camera.CameraGizmoView;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the game camera to the gizmo editor while it is open.
 *
 * The editor orbits around the lens being adjusted, which cannot be done by
 * moving the player: cameras are usually mounted where nobody can stand, and
 * half the handles would sit behind the block. Overriding the view for the
 * duration leaves the player exactly where they were.
 *
 * The setters are protected on Camera, so they are shadowed rather than
 * reached through an accessor interface — an interface mixin cannot target a
 * class, which is what crashed the first attempt at this.
 */
@Mixin(Camera.class)
public abstract class MixinGizmoCamera {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void nodewire$gizmoView(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            CallbackInfo ci
    ) {
        if (!CameraGizmoSession.isActive() || !CameraGizmoSession.getViewControlled()) return;
        Vec3 pos = CameraGizmoView.position();
        setPosition(pos.x, pos.y, pos.z);
        setRotation(CameraGizmoView.getYaw(), CameraGizmoView.getPitch());
    }
}
