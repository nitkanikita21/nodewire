package dev.nitka.nodewire.script.host

import dev.nitka.nodewire.script.ScriptCompileResult
import dev.nitka.nodewire.script.ScriptCompiler
import dev.nitka.nodewire.script.ScriptEvalResult
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Thin **loader** for the script compiler backend.
 *
 * The actual compiler logic (everything that links `kotlin.script.experimental.*`
 * and `org.jetbrains.kotlin.*`) lives in [ScriptBackend], bundled as
 * `nodewire-compiler/backend.jar` inside this addon's jar. The embedded Kotlin
 * compiler does `getResource` on its own classes; under NeoForge mod classes are
 * served from a `union://` filesystem that cannot extract a single `.class`. The
 * fix (the standard embedding pattern) is to load the compiler from a dedicated
 * [URLClassLoader] over the **extracted real jars** (outside `union://`), where
 * `getResource` returns normal `jar://` URLs.
 *
 * This object therefore references ONLY java.*, the URLClassLoader, and core's
 * compiler SPI (single class identity via the parent loader). It must NEVER
 * reference `kotlin.script.experimental.*` / `org.jetbrains.kotlin.*`, so it
 * itself loads fine under `union://`.
 *
 * Parent-first delegation means kotlin-stdlib, the script facade
 * (`dev.nitka.nodewire.script.*`), `PinValue`, and the [ScriptCompiler]
 * interface resolve from the PARENT (single identity → the returned
 * [dev.nitka.nodewire.script.ScriptModule] is usable by the host), while the
 * Kotlin compiler + scripting API resolve from the URLClassLoader's jars.
 */
object ScriptHost : ScriptCompiler {

    /** Stable, per-version extraction dir for the bundled compiler/lib jars. */
    private val cacheDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nodewire-compiler-2.0.20").apply { mkdirs() }
    }

    /** Lazily built once: extract jars, build the URLClassLoader, instantiate the backend. */
    private val backend: ScriptCompiler by lazy { loadBackend() }

    override fun compileToModule(source: String): ScriptCompileResult =
        backend.compileToModule(source)

    override fun evalSource(source: String): ScriptEvalResult =
        backend.evalSource(source)

    private fun loadBackend(): ScriptCompiler {
        val jars = extractBundledJars()

        // script-api.jar is the COMPILE classpath for the script body only; it
        // must NOT go on the URLClassLoader (duplicating the facade there would
        // give it a second identity and break PinValue/ScriptModule identity
        // crossing the sandbox boundary). The facade comes from the parent.
        val cpUrls: Array<URL> = jars
            .filter { it.name != SCRIPT_API_JAR }
            .map { it.toURI().toURL() }
            .toTypedArray()

        // Tell the backend where to find the extracted stdlib/script-runtime/
        // script-api jars for the script's compile classpath.
        System.setProperty(LIBS_DIR_PROP, cacheDir.absolutePath)

        val loader = BackendClassLoader(cpUrls, ScriptHost::class.java.classLoader)
        val cls = loader.loadClass(BACKEND_FQN)
        return cls.getDeclaredConstructor().newInstance() as ScriptCompiler
    }

    /**
     * Child-first loader for the compiler + backend, parent-delegating ONLY the
     * shared-identity surface.
     *
     * Default `URLClassLoader` is parent-first, which is wrong here: in a flat
     * test classpath the parent already has `ScriptBackend` (this module's
     * compile output) but NOT the compiler (`kotlin-scripting-jvm-host` is
     * `compileOnly`), so a parent-first `ScriptBackend` fails with
     * `NoClassDefFoundError: BasicJvmScriptingHost`. Under NeoForge the parent's
     * compiler self-resource lookups go through `union://` and break. Either way
     * the compiler + backend MUST come from our extracted jars.
     *
     * Delegated to the PARENT (single identity / not present in our jars):
     *  - `dev.nitka.nodewire.script.*` EXCEPT `.host.` / `.sandbox.` — the core
     *    facade (`ScriptModule`, `PinValue`-adjacent, the `ScriptCompiler` SPI,
     *    `ScriptEvalResult`). Identity here is load-bearing: the host uses the
     *    returned `ScriptModule`, and the sandbox asserts on `PinValue.Redstone`.
     *  - `kotlin.*` stdlib EXCEPT `kotlin.script.*` (the scripting API, which the
     *    parent lacks — it lives in our jars). Sharing stdlib avoids a duplicate
     *    `kotlin.Unit` identity.
     *  - `java.*` / `javax.*` / `jdk.*` / `sun.*` — the platform.
     *
     * Everything else (`org.jetbrains.kotlin.*`, `kotlin.script.*`,
     * `dev.nitka.nodewire.script.host/sandbox.*`, intellij/trove/guava bundled in
     * the compiler) loads CHILD-FIRST from our jars.
     */
    private class BackendClassLoader(urls: Array<URL>, parent: ClassLoader) :
        URLClassLoader(urls, parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { if (resolve) resolveClass(it); return it }
                val c = if (delegateToParent(name)) {
                    super.loadClass(name, false) // parent-first path
                } else {
                    runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
                }
                if (resolve) resolveClass(c)
                c
            }

        private fun delegateToParent(name: String): Boolean {
            if (name.startsWith("java.") || name.startsWith("javax.") ||
                name.startsWith("jdk.") || name.startsWith("sun.")
            ) return true
            if (name.startsWith("dev.nitka.nodewire.script.")) {
                // backend/sandbox load from our jars; the rest (facade/SPI) is parent.
                return !name.startsWith("dev.nitka.nodewire.script.host.") &&
                    !name.startsWith("dev.nitka.nodewire.script.sandbox.")
            }
            if (name.startsWith("kotlin.")) {
                // scripting API isn't on the parent — it lives in our jars.
                return !name.startsWith("kotlin.script.")
            }
            return false
        }
    }

    /**
     * Extract every jar listed in `nodewire-compiler/index.txt` to [cacheDir]
     * and return the resulting files. (Classpath resource directory listing is
     * unreliable under jar/union loaders, hence the generated index.)
     */
    private fun extractBundledJars(): List<File> {
        val cl = ScriptHost::class.java.classLoader
        val index = cl.getResourceAsStream("$RESOURCE_DIR/$INDEX_FILE")
            ?.bufferedReader()?.use { it.readLines() }
            ?: error("nodewire-compiler/$INDEX_FILE missing from the addon jar")

        return index.map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            val out = File(cacheDir, name)
            if (!out.exists() || out.length() == 0L) {
                val res = cl.getResourceAsStream("$RESOURCE_DIR/$name")
                    ?: error("nodewire-compiler/$name listed in index but missing from the jar")
                res.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
    }

    private const val RESOURCE_DIR = "nodewire-compiler"
    private const val INDEX_FILE = "index.txt"
    private const val SCRIPT_API_JAR = "script-api.jar"
    private const val BACKEND_FQN = "dev.nitka.nodewire.script.host.ScriptBackend"
    private const val LIBS_DIR_PROP = "nodewire.script.libsDir"
}
