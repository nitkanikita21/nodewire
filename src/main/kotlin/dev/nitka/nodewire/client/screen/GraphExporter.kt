package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Helpers for getting a graph out of the editor in a human-readable form.
 *
 * Both paths use the same conversion: `NodeGraph.CODEC` → NBT [CompoundTag]
 * → SNBT via [NbtUtils.structureToSnbt]. The SNBT is what a human (or LLM)
 * can read directly to diagnose a misbehaving graph.
 */
object GraphExporter {

    /**
     * Writes the graph's SNBT to `<gamedir>/nodewire-exports/<x>_<y>_<z>-<timestamp>.snbt`.
     * Returns the relative path (under gamedir) on success, or `null` if
     * encoding or the file write failed. Failures log a console warning.
     */
    fun exportToFile(graph: NodeGraph, pos: BlockPos): Path? {
        val snbt = toSnbt(graph) ?: return null
        val baseDir = FMLPaths.GAMEDIR.get().resolve("nodewire-exports")
        return try {
            Files.createDirectories(baseDir)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val name = "${pos.x}_${pos.y}_${pos.z}-$stamp.snbt"
            val file = baseDir.resolve(name)
            Files.writeString(file, snbt)
            // Return path relative to gamedir for user-friendly chat output.
            FMLPaths.GAMEDIR.get().relativize(file)
        } catch (t: Throwable) {
            System.err.println("[Nodewire] export failed: ${t.message}")
            null
        }
    }

    /**
     * Sets the system clipboard to the graph's SNBT. Returns true on
     * success. Uses MC's KeyboardHandler which dispatches through GLFW
     * synchronously on the client thread.
     */
    fun copyToClipboard(graph: NodeGraph): Boolean {
        val snbt = toSnbt(graph) ?: return false
        Minecraft.getInstance().keyboardHandler.clipboard = snbt
        return true
    }

    private fun toSnbt(graph: NodeGraph): String? {
        val tag = NodeGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).result().orElse(null)
            as? CompoundTag ?: return null
        return NbtUtils.structureToSnbt(tag)
    }
}
