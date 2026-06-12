package dev.nitka.nodewire.script

import dev.nitka.nodewire.script.host.ScriptHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `ui {}` flexbox DSL must compile inside a real script WITHOUT explicit
 * imports (script.ui.* is a default import) — this exercises the script-api
 * classpath (UiSpec/UiScope metadata must deserialize with zero Yoga/client
 * types on the script compiler's classpath) and the sandbox linkage path.
 */
class VideoUiScriptCompileTest {

    private fun diag(r: ScriptCompileResult): String =
        (r as? ScriptCompileResult.Failure)?.diagnostics?.joinToString("\n") ?: "(success)"

    @Test
    fun uiDslCompilesInsideClientBehavior() {
        val src = """
            val cam = input<Video>("video")
            val screen = output<Video>("out")

            clientBehavior {
                while (true) {
                    draw(screen) {
                        clear(0xFF101010)
                        ui(pad = 2, gap = 2) {
                            row(justify = Justify.SpaceBetween, bg = 0xFF000000) {
                                text("RWS-01", 0xFF8BE08B)
                                text("ZOOM 1×", 0xFF8BE08B)
                            }
                            spacer()
                            image(cam.value, height = 40)
                            row(gap = 4, align = Align.Center) {
                                text("AZ 0°")
                                spacer()
                                rect(4, 4, 0xFFFF3B3B)
                            }
                        }
                    }
                    frame()
                }
            }
        """.trimIndent()

        val compiled = ScriptHost.compileToModule(src)
        assertTrue(
            compiled is ScriptCompileResult.Success,
            "ui{} script failed to compile:\n${diag(compiled)}",
        )
    }
}
