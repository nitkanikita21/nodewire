package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Local-only graph storage. Saves to `<gamedir>/nodewire-graphs/<name>.snbt`
 * so the player can move graphs between blocks without round-tripping
 * through the server. The format is the same SNBT wrapper used by
 * [GraphClipboard] so files and clipboards are interchangeable.
 */
object GraphFiles {

    private const val EXT = "snbt"

    private fun dir(): Path = FMLPaths.GAMEDIR.get().resolve("nodewire-graphs")

    fun sanitize(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ' ' || c == '.') c else '_'
        }.joinToString("")
    }

    fun list(): List<String> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension.equals(EXT, ignoreCase = true) }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
        }
    }

    fun save(name: String, graph: NodeGraph): Path? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        return try {
            Files.createDirectories(dir())
            val file = dir().resolve("$safe.$EXT")
            Files.writeString(file, encode(graph))
            file
        } catch (t: Throwable) {
            System.err.println("[Nodewire] graph save failed: ${t.message}")
            null
        }
    }

    fun load(name: String): NodeGraph? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        val file = dir().resolve("$safe.$EXT")
        return try {
            if (!Files.isRegularFile(file)) return null
            decode(Files.readString(file))
        } catch (t: Throwable) {
            System.err.println("[Nodewire] graph load failed: ${t.message}")
            null
        }
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name)
        if (safe.isEmpty()) return false
        val file = dir().resolve("$safe.$EXT")
        return try {
            Files.deleteIfExists(file)
        } catch (_: Throwable) {
            false
        }
    }

    fun exists(name: String): Boolean {
        val safe = sanitize(name)
        if (safe.isEmpty()) return false
        return Files.isRegularFile(dir().resolve("$safe.$EXT"))
    }

    private const val MARKER = "nodewire_graph"
    private const val VERSION_KEY = "version"
    private const val GRAPH_KEY = "graph"
    private const val CURRENT_VERSION = 1

    private fun encode(graph: NodeGraph): String {
        val payload = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph)
            .result().orElseThrow()
        val wrapper = CompoundTag().apply {
            putBoolean(MARKER, true)
            putInt(VERSION_KEY, CURRENT_VERSION)
            put(GRAPH_KEY, payload)
        }
        return wrapper.toString()
    }

    private fun decode(raw: String): NodeGraph? {
        val parsed = runCatching { TagParser.parseTag(raw) }.getOrNull() ?: return null
        val wrapper = parsed as? CompoundTag ?: return null
        if (!wrapper.getBoolean(MARKER)) return null
        val graphTag = wrapper.get(GRAPH_KEY) ?: return null
        return NodeGraph.CODEC.parse(NbtOps.INSTANCE, graphTag).result().orElse(null)
    }
}
