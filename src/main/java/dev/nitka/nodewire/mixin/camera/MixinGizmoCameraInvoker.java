package dev.nitka.nodewire.mixin.camera;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Bridges {@link MixinGizmoCameraAccess} to Camera's protected setters. */
@Mixin(Camera.class)
public interface MixinGizmoCameraInvoker extends MixinGizmoCameraAccess {

    @Invoker("setPosition")
    void nodewire$invokeSetPosition(double x, double y, double z);

    @Invoker("setRotation")
    void nodewire$invokeSetRotation(float yaw, float pitch);

    @Override
    default void nodewire$setPosition(double x, double y, double z) {
        nodewire$invokeSetPosition(x, y, z);
    }

    @Override
    default void nodewire$setRotation(float yaw, float pitch) {
        nodewire$invokeSetRotation(yaw, pitch);
    }
}
