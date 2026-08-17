package dev.nitka.nodewire.client.video;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * "Is the level being drawn from a viewpoint other than the player's right
 * now?"
 *
 * <p>Sodium keeps one visible-section list and one draw-command batch per
 * region, both filled from the rendering camera, and its invalidation check
 * cannot tell two cameras apart for regions neither of them stands inside —
 * the whole render-distance frontier. Every mod that draws a second view per
 * frame therefore has to hand that view its own copies; Immersive Portals had
 * to multiply the lists for portal layers, and Veil keeps a separate one for
 * its perspective renders.
 *
 * <p>Our Sodium mixin does the same, and this is what tells it when. Nodewire
 * captures are one source; Vista's live feeds are another, and its own
 * cameras exhibit the identical flicker in packs where Sodium is present —
 * verified in this modpack with no Nodewire camera involved at all. Since the
 * isolation is strictly additive (the second view gets private structures and
 * writes nothing the main view reads), extending it to cover Vista's renders
 * fixes those too rather than only our own.
 *
 * <p>Vista's check is a public static predicate, reached through a
 * {@link MethodHandle} resolved once; absent Vista, the handle stays null and
 * this reduces to the capture flag.
 */
public final class SecondaryView {

    private SecondaryView() {
    }

    private static volatile boolean resolved;
    private static MethodHandle vistaRenderingFeed;

    private static MethodHandle handle() {
        if (!resolved) {
            synchronized (SecondaryView.class) {
                if (!resolved) {
                    resolved = true;
                    try {
                        Class<?> cls = Class.forName(
                                "net.mehvahdjukaar.vista.client.renderer.VistaLevelRenderer");
                        vistaRenderingFeed = MethodHandles.lookup()
                                .findStatic(cls, "isRenderingLiveFeed", MethodType.methodType(boolean.class));
                    } catch (Throwable ignored) {
                        vistaRenderingFeed = null;
                    }
                }
            }
        }
        return vistaRenderingFeed;
    }

    /** True while any secondary view is rendering (ours or Vista's). */
    public static boolean active() {
        if (VideoManager.isCapturing()) return true;
        MethodHandle h = handle();
        if (h == null) return false;
        try {
            return (boolean) h.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
