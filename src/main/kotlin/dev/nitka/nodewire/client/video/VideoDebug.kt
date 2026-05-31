package dev.nitka.nodewire.client.video

import com.mojang.logging.LogUtils
import net.minecraft.Util
import org.slf4j.Logger

/**
 * Throttled diagnostics for the video pipeline (temporary, debugging the first
 * end-to-end). Logs at most once per ~2 s per [key] so it never spams a frame
 * loop. Remove once the pipeline is verified.
 */
object VideoDebug {
    private val LOG: Logger = LogUtils.getLogger()
    private val last = HashMap<String, Long>()

    fun log(key: String, msg: () -> String) {
        val now = Util.getMillis()
        synchronized(last) {
            val prev = last[key]
            if (prev != null && now - prev < 2000L) return
            last[key] = now
        }
        LOG.info("NW-VIDEO [{}] {}", key, msg())
    }
}
