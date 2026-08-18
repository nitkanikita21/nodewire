package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.CameraGizmoSession;
import dev.nitka.nodewire.client.camera.CameraGizmoView;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the game camera to the gizmo editor while it is open.
 *
 * The editor orbits around the lens being adjusted, which cannot be done by
 * moving the player: the camera is usually mounted somewhere unreachable, and
 * half its handles would sit behind the block. Overriding the view for the
 * duration leaves the player exactly where they were.
 */
@Mixin(Camera.class)
public abstract class MixinGizmoCamera {

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
        Camera self = (Camera) (Object) this;
        Vec3 pos = CameraGizmoView.position();
        // Mixins are merged into the target, so the protected setters are ours
        // to call here.
        ((MixinGizmoCameraAccess) self).nodewire$setPosition(pos.x, pos.y, pos.z);
        ((MixinGizmoCameraAccess) self).nodewire$setRotation(CameraGizmoView.getYaw(), CameraGizmoView.getPitch());
    }
}
