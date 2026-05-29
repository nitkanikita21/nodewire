package dev.nitka.nodewire.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.nitka.nodewire.signal.VirtualSignalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Layers our {@link VirtualSignalMap} onto {@link BlockBehaviour.BlockStateBase#getSignal}
 * and {@code getDirectSignal} — the real vanilla methods the {@code SignalGetter}
 * interface defaults delegate to.
 *
 * <p><b>Why here and not on Level/ServerLevel.</b> Earlier attempts wrapped
 * {@code getSignal} on {@link Level} (added method) and {@code ServerLevel}
 * (another mod's added method) and the {@code SignalGetter} interface default.
 * In the modpack none of those caught a per-face {@code getSignal} read:
 * DriveByWire {@code @Mixin(ServerLevel)} overrides {@code getSignal}, and its
 * {@code super.getSignal(...)} resolves to the {@code SignalGetter} <em>default</em>,
 * bypassing our {@code Level} copy. We can't inject the interface default (Sponge
 * Mixin doesn't apply it here) and we can't reliably target a method another mod
 * <em>adds</em> to {@code ServerLevel} (cross-config injector resolution). Hard
 * evidence: with the value in the map, {@code getSignal} returned 0 while
 * {@code getDirectSignal}/{@code getBestNeighborSignal} returned our 15, so
 * Create Simulated's Directional Gearshift (reads strictly per-face via
 * {@code getSignal}) never powered, though the lamp (reads {@code hasNeighborSignal})
 * did.
 *
 * <p>The fix: the interface default {@code getSignal} body is
 * {@code int i = blockState.getSignal(this, pos, dir); ...} — so it ALWAYS calls
 * {@link BlockBehaviour.BlockStateBase#getSignal}, a concrete vanilla method.
 * Whoever invokes {@code getSignal} (direct dispatch, DriveByWire's {@code super},
 * a perf mod's reimplementation that still defers to vanilla) flows through here.
 * {@code @ModifyReturnValue} on a real method binds cleanly and stacks with any
 * other mod's wrap. {@code Math.max(original, virtual)} only ever ADDS our
 * injected power — with no binding for {@code (queryer, dir)} the lookup is 0 and
 * the original is returned untouched.
 *
 * <p>{@code pos} is the queried neighbour block and {@code dir} the query
 * direction. Resolving which {@code (target, targetSide)} binding that maps to is
 * delegated to {@link VirtualSignalMap.MapData#powerForQuery}, which probes both
 * the standard convention (vanilla {@code hasNeighborSignal}, Create gearshift:
 * {@code getSignal(target.relative(side), side)}) and the flipped one used by
 * Offroad's wheel-mount steering ({@code getSignal(pos.relative(left), right)}
 * with {@code right == left.opposite()}). Bindings are written with the Channel
 * Link Tool as {@code (target, targetSide)}.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {

    @ModifyReturnValue(
        method = "getSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
        at = @At("RETURN")
    )
    private int nodewire$layerSignal(int original, BlockGetter level, BlockPos pos, Direction direction) {
        return nodewire$layer(original, level, pos, direction);
    }

    @ModifyReturnValue(
        method = "getDirectSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
        at = @At("RETURN")
    )
    private int nodewire$layerDirectSignal(int original, BlockGetter level, BlockPos pos, Direction direction) {
        return nodewire$layer(original, level, pos, direction);
    }

    private int nodewire$layer(int original, BlockGetter level, BlockPos pos, Direction direction) {
        if (!(level instanceof Level self) || self.isClientSide) return original;
        // powerForQuery probes both the standard and the flipped getSignal
        // direction conventions — see VirtualSignalMap.powerForQuery. (pos, direction)
        // are passed verbatim from the getSignal/getDirectSignal call site.
        int virtual = VirtualSignalMap.INSTANCE.of(self).powerForQuery(pos, direction);
        return Math.max(original, virtual);
    }
}
