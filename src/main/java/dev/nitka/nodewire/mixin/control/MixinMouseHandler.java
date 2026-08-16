package dev.nitka.nodewire.mixin.control;

import dev.nitka.nodewire.client.control.ControlSession;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes the player's head while a Control Block session has the mouse
 * captured. {@code CalculatePlayerTurnEvent} can't do this (its sensitivity is
 * clamped by a {@code *0.6+0.2} term, so even 0 still turns), so we cancel
 * {@code turnPlayer} outright and hand the raw accumulated mouse delta to
 * {@link ControlSession} for the virtual aim look. The caller
 * ({@code handleAccumulatedMovement}) zeroes the accumulators immediately after,
 * so releasing capture doesn't produce a jump.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void nodewire$freezeHeadWhileCapturing(double movementTime, CallbackInfo ci) {
        ControlSession session = ControlSession.INSTANCE;
        if (session.isActive() && session.getMouseCaptured()) {
            session.addLookDelta(this.accumulatedDX, this.accumulatedDY);
            ci.cancel();
            return;
        }
        // Panel joystick HOLD session: same freeze, deltas become stick_x/y.
        var joystick = dev.nitka.nodewire.client.panel.PanelJoystickSession.INSTANCE;
        if (joystick.isActive()) {
            joystick.addLookDelta(this.accumulatedDX, this.accumulatedDY);
            ci.cancel();
        }
    }
}
