package dev.nitka.nodewire.ui.core

import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.client.Minecraft
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main.immediate` analogue for the Minecraft client thread.
 *
 * Compose's recomposer must run on a single thread for snapshot consistency.
 * MC's `Minecraft` instance also requires every UI/world mutation to happen
 * on its client thread, so we serialize both onto that thread.
 *
 * If we're already on the client thread, [dispatch] runs the block
 * synchronously rather than re-queueing — this is the "immediate" behavior
 * Compose relies on to finish a recomposition without yielding mid-pass.
 */
class NwClientDispatcher : CoroutineDispatcher() {
    private val mc: Minecraft = Minecraft.getInstance()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !mc.isSameThread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (mc.isSameThread) block.run() else mc.execute(block)
    }
}
