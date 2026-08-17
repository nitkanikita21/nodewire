package dev.nitka.nodewire.mixin.sodium;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Applies the Sodium mixins only when Sodium is actually present. They are
 * compiled against Sodium's real classes (the reflective/{@code @Pseudo}
 * variants silently failed to apply, because the members involved are typed
 * with classes that live inside Sodium's jarJar), so without this gate their
 * class loading would fail in packs without Sodium.
 */
public class SodiumMixinPlugin implements IMixinConfigPlugin {

    private boolean sodiumPresent;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            MixinService.getService().getBytecodeProvider()
                    .getClassNode("net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion");
            sodiumPresent = true;
        } catch (Throwable t) {
            sodiumPresent = false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sodiumPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
