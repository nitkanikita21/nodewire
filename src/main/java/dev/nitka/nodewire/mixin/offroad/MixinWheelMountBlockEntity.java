package dev.nitka.nodewire.mixin.offroad;

import dev.nitka.nodewire.graph.PinType;
import dev.nitka.nodewire.graph.PinValue;
import dev.nitka.nodewire.integration.offroad.WheelMountPinGlue;
import dev.nitka.nodewire.link.LinkContext;
import dev.nitka.nodewire.link.LinkPin;
import dev.nitka.nodewire.link.PinLink;
import dev.nitka.nodewire.link.PinLinkEngine;
import dev.nitka.nodewire.link.PinLinkScratch;
import dev.nitka.nodewire.link.PinLinkSink;
import dev.nitka.nodewire.link.PinReading;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes offroad's Wheel Mount a {@link PinLinkSink} (hence {@code PinPort}) so
 * the Channel Link Tool drives its steering and reads its live angle — the
 * BE-local case from the host-less-pin-links spec's "Parallel track" section
 * (a mixin-able foreign consumer prefers a per-BE sink over the level store, so
 * its links are schematic-safe through the existing {@code SablePinLinkMapper}
 * with zero extra work).
 *
 * <p>Bound to the target by name ({@code targets}) — offroad is an optional
 * compile-absent mod, so we duck-type onto {@code WheelMountBlockEntity} and
 * cast {@code (BlockEntity)(Object)this} for the vanilla BE surface. The whole
 * config is {@code required: false}, so the game runs fine without offroad.
 *
 * <p>Every {@code PinPort}/{@code PinLinkSink} member is implemented
 * explicitly: the Kotlin interface defaults compile to {@code DefaultImpls}
 * (no {@code -Xjvm-default=all} in this project), which do NOT carry onto a
 * Java target — a missing one would be an {@code AbstractMethodError} at link.
 * The {@link PinValue}/{@link PinLink} codec glue lives in
 * {@link WheelMountPinGlue} (Java↔Kotlin-companion access is awkward).
 *
 * <h3>Pins</h3>
 * <ul>
 *   <li>OUT {@code steering_angle} (FLOAT) — the shadowed {@code angle} field.</li>
 *   <li>OUT {@code position}/{@code position_text} (VEC3/STRING) — Sable-aware
 *       world centre (the BE is a {@code BlockEntitySubLevelActor}).</li>
 *   <li>IN {@code target_steering} (FLOAT) — stored into the
 *       {@link #nodewire$steeringOverridden override box}; {@code getSteeringSignal}
 *       returns it (HEAD, cancellable) while set, else falls through to vanilla
 *       redstone. Both the visual {@code angle} and the server physics flow
 *       through {@code getSteeringSignal}, so one override covers both.</li>
 * </ul>
 */
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
public abstract class MixinWheelMountBlockEntity implements PinLinkSink {

    /** Live wheel angle (degrees) — lerp-overwritten each tick by the BE. */
    @Shadow private double angle;

    /** Vanilla's cached steering signal + its client-sync. The VISUAL wheel angle
     *  is driven client-side from the synced signal (not from the server's
     *  chasingYaw), so the override must update these + sendData to be seen. */
    @Shadow private int lastServerSteeringSignal;
    @Shadow private int lastServerSteeringSignalLeft;
    @Shadow private int lastServerSteeringSignalRight;

    /** Links feeding this mount's input pins (persisted via Create write/read). */
    @Unique private final List<PinLink> nodewire$pinLinks = new ArrayList<>();

    /** Engine clear/quiescent bookkeeping. */
    @Unique private final PinLinkScratch nodewire$pinLinkScratch = new PinLinkScratch();

    /** Steering override box: while {@code overridden}, {@code getSteeringSignal}
     *  returns {@code value} instead of reading the side faces. */
    @Unique private boolean nodewire$steeringOverridden = false;
    @Unique private int nodewire$steeringValue = 0;
    /** Last value pushed to clients — so we only sendData on a change, not every tick. */
    @Unique private int nodewire$lastSyncedSteering = Integer.MIN_VALUE;

    // ── PinPort ───────────────────────────────────────────────────────────

    @Override
    public List<LinkPin> pinOutputs(LinkContext ctx) {
        return WheelMountPinGlue.pinOutputs();
    }

    @Override
    public List<LinkPin> pinInputs(LinkContext ctx) {
        return WheelMountPinGlue.pinInputs();
    }

    @Override
    public PinReading readPin(String id) {
        if (WheelMountPinGlue.STEERING_ANGLE_PIN.equals(id)) {
            return WheelMountPinGlue.steeringAngleReading(this.angle);
        }
        if (WheelMountPinGlue.POSITION_PIN.equals(id)) {
            return WheelMountPinGlue.positionReading((BlockEntity) (Object) this);
        }
        if (WheelMountPinGlue.POSITION_TEXT_PIN.equals(id)) {
            return WheelMountPinGlue.positionTextReading((BlockEntity) (Object) this);
        }
        return null;
    }

    @Override
    public void writePin(String id, PinValue value) {
        if (!WheelMountPinGlue.TARGET_STEERING_PIN.equals(id)) return;
        Integer steering = WheelMountPinGlue.targetSteering(value);
        if (steering == null) return;
        this.nodewire$steeringOverridden = true;
        this.nodewire$steeringValue = steering;
    }

    @Override
    public void clearPin(String id) {
        if (WheelMountPinGlue.TARGET_STEERING_PIN.equals(id)) {
            this.nodewire$steeringOverridden = false;
            this.nodewire$lastSyncedSteering = Integer.MIN_VALUE;
        }
    }

    // ── PinLinkSink ───────────────────────────────────────────────────────

    @Override
    public List<PinLink> pinLinks() {
        return this.nodewire$pinLinks;
    }

    @Override
    public void onPinLinksChanged() {
        ((BlockEntity) (Object) this).setChanged();
    }

    @Override
    public PinLinkScratch getPinLinkScratch() {
        return this.nodewire$pinLinkScratch;
    }

    @Override
    public boolean acceptsSource(String targetPin, PinType srcType) {
        return true;
    }

    // ── injectors ─────────────────────────────────────────────────────────

    /** Override the steering signal when a pin feeds it; else fall through to
     *  vanilla redstone (server) / clientSteeringSignal (client). */
    @Inject(method = "getSteeringSignal", at = @At("HEAD"), cancellable = true)
    private void nodewire$overrideSteering(CallbackInfoReturnable<Integer> cir) {
        if (!this.nodewire$steeringOverridden) return;
        int v = this.nodewire$steeringValue;
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level != null && !level.isClientSide) {
            // Mirror vanilla's cache so the BE's client sync replicates the
            // override (net = left - right). Without this only the server's
            // chasingYaw turns and the visual wheel stays straight.
            this.lastServerSteeringSignal = v;
            this.lastServerSteeringSignalLeft = v > 0 ? v : 0;
            this.lastServerSteeringSignalRight = v < 0 ? -v : 0;
            if (v != this.nodewire$lastSyncedSteering) {
                // sendBlockUpdated triggers Create's getUpdateTag -> write(clientPacket)
                // -> read(clientPacket) on the client (sendData is a super method we
                // can't @Shadow off the target, so go through the vanilla path).
                level.sendBlockUpdated(self.getBlockPos(), self.getBlockState(), self.getBlockState(), Block.UPDATE_CLIENTS);
                this.nodewire$lastSyncedSteering = v;
            }
        }
        cir.setReturnValue(v);
    }

    /** Drive pin-link delivery from the BE's own server ticker. MUST be HEAD,
     *  not TAIL: WheelMount.tick() returns early on the server (right after the
     *  isClientSide check), so its only TAIL is in the client physics path —
     *  a TAIL inject never fires server-side and the steering link never
     *  delivers. HEAD fires on both sides; the isClientSide guard below keeps it
     *  server-effective, and running before the BE's chasingYaw lerp means the
     *  override lands the same tick it's read. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void nodewire$tickLinks(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        PinLinkEngine.INSTANCE.tick(level, self);
    }

    /** Persist only the links (not the override) — the override is re-derived
     *  from the live link each tick. Persisting it would leave the wheel stuck
     *  at the last value if the link is removed while the BE is unloaded, since
     *  clearPin never fires on reload (the engine's scratch starts empty). */
    @Inject(
        method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
        at = @At("TAIL")
    )
    private void nodewire$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        WheelMountPinGlue.writePinLinks(tag, this.nodewire$pinLinks);
    }

    @Inject(
        method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
        at = @At("TAIL")
    )
    private void nodewire$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        WheelMountPinGlue.readPinLinks(tag, this.nodewire$pinLinks);
    }
}
