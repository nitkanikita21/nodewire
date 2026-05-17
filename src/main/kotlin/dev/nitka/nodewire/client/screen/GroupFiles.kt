package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.GroupTemplate
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Local-only template storage under `<gamedir>/nodewire-groups/`.
 * Same envelope pattern as [GraphFiles] for symmetry — `nodewire_group`
 * marker + `version` + `template` payload.
 */
object GroupFiles {

    private const val EXT = "snbt"

    private fun dir(): Path = FMLPaths.GAMEDIR.get().resolve("nodewire-groups")

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

    fun save(name: String, template: GroupTemplate): Path? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        return try {
            Files.createDirectories(dir())
            val file = dir().resolve("$safe.$EXT")
            Files.writeString(file, encode(template))
            file
        } catch (t: Throwable) {
            System.err.println("[Nodewire] group save failed: ${t.message}")
            null
        }
    }

    fun load(name: String): GroupTemplate? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        val file = dir().resolve("$safe.$EXT")
        return try {
            if (!Files.isRegularFile(file)) return null
            decode(Files.readString(file))
        } catch (t: Throwable) {
            System.err.println("[Nodewire] group load failed: ${t.message}")
            null
        }
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name)
        if (safe.isEmpty()) return false
        return try { Files.deleteIfExists(dir().resolve("$safe.$EXT")) } catch (_: Throwable) { false }
    }

    private const val MARKER = "nodewire_group"
    private const val VERSION_KEY = "version"
    private const val PAYLOAD_KEY = "template"
    private const val CURRENT_VERSION = 1

    private fun encode(t: GroupTemplate): String {
        val payload = GroupTemplate.CODEC.encodeStart(NbtOps.INSTANCE, t)
            .result().orElseThrow()
        val wrapper = CompoundTag().apply {
            putBoolean(MARKER, true)
            putInt(VERSION_KEY, CURRENT_VERSION)
            put(PAYLOAD_KEY, payload)
        }
        return wrapper.toString()
    }

    private fun decode(raw: String): GroupTemplate? {
        val parsed = runCatching { TagParser.parseTag(raw) }.getOrNull() ?: return null
        val wrapper = parsed as? CompoundTag ?: return null
        if (!wrapper.getBoolean(MARKER)) return null
        val payload = wrapper.get(PAYLOAD_KEY) ?: return null
        return GroupTemplate.CODEC.parse(NbtOps.INSTANCE, payload).result().orElse(null)
    }
}
