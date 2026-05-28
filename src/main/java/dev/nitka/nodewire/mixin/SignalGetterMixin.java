package dev.nitka.nodewire.mixin;

import dev.nitka.nodewire.signal.VirtualSignalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds concrete overrides of two {@code SignalGetter} default methods onto
 * {@link Level}. Vanilla puts the methods on the interface as defaults, so
 * Mixin's @Inject can't be used (interface injectors are unsupported by
 * Spongepowered Mixin). Instead, we add new methods directly to Level —
 * since interface defaults are only invoked when no implementor overrides,
 * adding our methods makes Level's overrides win every dispatch.
 *
 * Both methods preserve vanilla behaviour by re-implementing the default
 * logic (six-face scan + max), then layer our virtual map on top. We could
 * call {@code SignalGetter.super.getBestNeighborSignal(pos)} but the bytecode
 * verifier in dev environments is fussy about cross-package `super` so
 * inlining the same logic is more robust.
 */
@Mixin(Level.class)
public abstract class SignalGetterMixin {

    /**
     * Re-implementation of {@link net.minecraft.world.level.SignalGetter#getBestNeighborSignal}
     * augmented with our virtual signal map. By declaring it on Level we shadow
     * the interface default, so every callsite that goes through a Level
     * instance picks this up.
     */
    public int getBestNeighborSignal(BlockPos pos) {
        Level self = (Level) (Object) this;
        int best = 0;
        for (Direction d : Direction.values()) {
            int s = self.getSignal(pos.relative(d), d);
            if (s > best) best = s;
            if (best >= 15) return 15;
        }
        if (!self.isClientSide) {
            int virtual = VirtualSignalMap.INSTANCE.of(self).strongestAt(pos);
            if (virtual > best) best = virtual;
        }
        return best;
    }

    /**
     * Per-face signal query. Vanilla path: ask the block at {@code pos}
     * what signal it emits in {@code direction}. We layer virtual signal on
     * top: if there's a binding driving (queryer, direction), surface that.
     *
     * Queryer is at {@code pos.relative(direction.getOpposite())}. The face
     * of the queryer that touches {@code pos} is exactly {@code direction}.
     * That's the key we wrote into VirtualSignalMap.put(...).
     *
     * Without this override blocks like Simulated's Directional Gearshift
     * that inspect a single specific incoming face via {@code Level.getSignal}
     * (not the aggregate {@code hasNeighborSignal}) miss our injection.
     */
    public int getSignal(BlockPos pos, Direction direction) {
        Level self = (Level) (Object) this;
        BlockState state = self.getBlockState(pos);
        int vanilla = state.getSignal(self, pos, direction);
        if (!self.isClientSide) {
            BlockPos queryer = pos.relative(direction.getOpposite());
            int virtual = VirtualSignalMap.INSTANCE.of(self).powerAtFace(queryer, direction);
            if (virtual > vanilla) vanilla = virtual;
        }
        return vanilla;
    }

    public boolean hasNeighborSignal(BlockPos pos) {
        Level self = (Level) (Object) this;
        for (Direction d : Direction.values()) {
            if (self.getSignal(pos.relative(d), d) > 0) return true;
        }
        if (!self.isClientSide
            && VirtualSignalMap.INSTANCE.of(self).strongestAt(pos) > 0) {
            return true;
        }
        return false;
    }
}
