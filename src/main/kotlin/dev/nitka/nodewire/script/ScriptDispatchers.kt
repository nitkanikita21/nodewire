package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * One shared worker pool for ALL script nodes (spec D5). Each node confines to
 * its own [nodeDispatcher] = `pool.limitedParallelism(1)` → parallelism ACROSS
 * nodes, cooperative WITHIN a node (a node's behaviors never run simultaneously,
 * so its plain vars + state cells are lock-free).
 *
 * Daemon threads so a leaked runaway (spec D9) can't block server shutdown.
 * Size above the expected concurrent-script count so one leak can't starve the
 * rest — same stance as ScriptExecutor (`scripting/.../ScriptExecutor.kt:19-22`).
 * Overridable for tests.
 */
object ScriptDispatchers {
    @Volatile
    var sharedDispatcher: CoroutineDispatcher =
        Executors.newFixedThreadPool(
            (Runtime.getRuntime().availableProcessors()).coerceIn(2, 8),
        ) { r -> Thread(r, "nw-script").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun nodeDispatcher(): CoroutineDispatcher = sharedDispatcher.limitedParallelism(1)
}
