package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.camerachunk.ClientCameraZones;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pillar 2 Stage A. The client's chunk cache is a circular buffer indexed by
 * {@code floorMod(x/z, viewRange)}; when the player is far from a camera zone the
 * zone chunk's slot gets overwritten and evicted. We keep a separate strong
 * {@code Map<Long, LevelChunk>} of streamed zone chunks so {@code getChunk}
 * always returns real data for the pinned RenderSection to compile (Stage B),
 * capture them on arrival, and refuse server-initiated drops.
 *
 * Ported from Vista's {@code ClientChunkCacheMixin}.
 */
@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache {

    @Unique
    private final Map<Long, LevelChunk> nodewire$pinned = new HashMap<>();

    /** Short-circuit getChunk for any pinned zone chunk before the ring lookup. */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"), cancellable = true)
    private void nodewire$getPinned(int x, int z, ChunkStatus status, boolean require, CallbackInfoReturnable<LevelChunk> cir) {
        if (nodewire$pinned.isEmpty()) return;
        if (ClientCameraZones.containsChunk(x, z)) {
            LevelChunk c = nodewire$pinned.get(ChunkPos.asLong(x, z));
            if (c != null) cir.setReturnValue(c);
        }
    }

    /** When the server streams a zone chunk, keep a permanent reference. */
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void nodewire$capturePinned(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                                        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
                                        CallbackInfoReturnable<LevelChunk> cir) {
        if (ClientCameraZones.containsChunk(x, z) && cir.getReturnValue() != null) {
            nodewire$pinned.put(ChunkPos.asLong(x, z), cir.getReturnValue());
        }
    }

    /** Don't let a server forget-packet clear a still-wanted zone chunk. */
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void nodewire$preventDrop(ChunkPos chunkPos, CallbackInfo ci) {
        if (!nodewire$pinned.isEmpty() && ClientCameraZones.containsChunk(chunkPos.x, chunkPos.z)) {
            ci.cancel();
        }
    }
}
