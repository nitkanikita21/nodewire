package dev.nitka.nodewire.mixin.camera;

/**
 * The two protected camera setters the gizmo view needs, exposed on the
 * vanilla Camera by {@code MixinGizmoCameraInvoker}.
 */
public interface MixinGizmoCameraAccess {
    void nodewire$setPosition(double x, double y, double z);

    void nodewire$setRotation(float yaw, float pitch);
}
