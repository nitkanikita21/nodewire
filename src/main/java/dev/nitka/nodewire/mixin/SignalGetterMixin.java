package dev.nitka.nodewire.mixin;

import dev.nitka.nodewire.signal.VirtualSignalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds concrete overrides of every {@code SignalGetter} default method onto
 * {@link Level}. Vanilla puts them on the interface as defaults, so Mixin's
 * @Inject can't be used (interface injectors are unsupported by Sponge
 * Mixin). Instead, we add the methods directly to Level — since interface
 * defaults are only invoked when no implementor overrides, adding them
 * here makes Level's copies win every dispatch.
 *
 * Each override preserves vanilla behaviour by re-implementing the default
 * logic, then layers our {@link VirtualSignalMap} on top so blocks of any
 * kind (vanilla, Create, mod X) that go through any of these APIs see the
 * injected virtual signals.
 *
 * Convention for the per-face methods (getSignal / getDirectSignal /
 * hasSignal / getControlInputSignal):
 *   pos      = neighbour position passed by vanilla.
 *   dir      = direction from queryer to neighbour.
 *   queryer  = pos.relative(dir.opposite()).
 *   virtual key (queryer, dir) — exactly the (target, targetSide) we wrote
 *   into VirtualSignalMap when binding a side via the Channel Link Tool.
 */
@Mixin(Level.class)
public abstract class SignalGetterMixin {

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

    public int getDirectSignal(BlockPos pos, Direction direction) {
        Level self = (Level) (Object) this;
        BlockState state = self.getBlockState(pos);
        int vanilla = state.getDirectSignal(self, pos, direction);
        if (!self.isClientSide) {
            BlockPos queryer = pos.relative(direction.getOpposite());
            int virtual = VirtualSignalMap.INSTANCE.of(self).powerAtFace(queryer, direction);
            if (virtual > vanilla) vanilla = virtual;
        }
        return vanilla;
    }

    public boolean hasSignal(BlockPos pos, Direction direction) {
        Level self = (Level) (Object) this;
        BlockState state = self.getBlockState(pos);
        if (state.getSignal(self, pos, direction) > 0) return true;
        if (!self.isClientSide) {
            BlockPos queryer = pos.relative(direction.getOpposite());
            if (VirtualSignalMap.INSTANCE.of(self).powerAtFace(queryer, direction) > 0) {
                return true;
            }
        }
        return false;
    }

    public int getControlInputSignal(BlockPos pos, Direction direction, boolean diodesOnly) {
        Level self = (Level) (Object) this;
        BlockState state = self.getBlockState(pos);
        // Honour vanilla's diodes-only short-circuit. We're not a diode, so
        // the virtual layer can't contribute to that branch.
        if (diodesOnly) {
            return net.minecraft.world.level.block.DiodeBlock.isDiode(state)
                ? self.getDirectSignal(pos, direction) : 0;
        }
        int vanilla;
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK)) {
            vanilla = 15;
        } else if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)) {
            vanilla = state.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
        } else {
            vanilla = state.isSignalSource()
                ? self.getDirectSignal(pos, direction)
                : 0;
        }
        if (!self.isClientSide) {
            BlockPos queryer = pos.relative(direction.getOpposite());
            int virtual = VirtualSignalMap.INSTANCE.of(self).powerAtFace(queryer, direction);
            if (virtual > vanilla) vanilla = virtual;
        }
        return vanilla;
    }

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

    public int getDirectSignalTo(BlockPos pos) {
        Level self = (Level) (Object) this;
        int sum = 0;
        for (Direction d : Direction.values()) {
            sum += self.getDirectSignal(pos.relative(d), d);
            if (sum >= 15) return 15;
        }
        return sum;
    }
}
