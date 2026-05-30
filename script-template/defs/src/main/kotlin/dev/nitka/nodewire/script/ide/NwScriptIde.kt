package dev.nitka.nodewire.script.ide

import dev.nitka.nodewire.script.ScriptModule
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * **IDE-only** script definition for `*.nw.kts` files.
 *
 * This is the editor-side twin of the mod's runtime definition
 * (`dev.nitka.nodewire.script.host.NwScript`). It carries the SAME two ergonomic
 * keys so a file you author in IntelliJ resolves exactly like the in-world editor:
 *
 *  - `defaultImports("dev.nitka.nodewire.script.*")` — no import line needed.
 *  - `implicitReceivers(ScriptModule)` — `this` is a [ScriptModule], so
 *    `input(...)`, `output(...)`, `state(...)`, `tick { }`, `log`/`chat` resolve
 *    unqualified, with full completion and type-checking.
 *
 * The classpath difference from the runtime definition is deliberate: here we use
 * `dependenciesFromCurrentContext(wholeClasspath = true)` — correct for a plain
 * Gradle/IntelliJ project — instead of the runtime's explicit extracted-jars
 * classpath (which exists only because NeoForge serves the mod from a `union://`
 * filesystem). The facade comes from `../libs/script-api.jar` on this module's
 * classpath.
 *
 * Discovery: `src/main/resources/META-INF/kotlin/script/templates/<FQN of this class>`
 * makes IntelliJ pick the definition up for any module that depends on this jar.
 */
@KotlinScript(
    fileExtension = "nw.kts",
    compilationConfiguration = NwScriptIdeConfig::class,
)
abstract class NwScriptIde

object NwScriptIdeConfig : ScriptCompilationConfiguration({
    defaultImports("dev.nitka.nodewire.script.*")
    implicitReceivers(ScriptModule::class)
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})
