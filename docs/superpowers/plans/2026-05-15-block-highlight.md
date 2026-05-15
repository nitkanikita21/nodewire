# Block Highlight — Implementation Plan

> **For agentic workers:** Stay on `master`. 4 tasks, one commit each.

**Goal:** Add a pulsing wireframe block highlight system with renderer, client command, and BindingsManagerScreen integration.

**Spec:** `docs/superpowers/specs/2026-05-15-block-highlight.md`

---

### Task 1: `BlockHighlightRenderer`

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt`

**Context:** `src/main/kotlin/dev/nitka/nodewire/client/wire/WireWorldRenderer.kt` is the template. Read it for the `Shards` private object trick (workaround for `protected` `RenderStateShard` constants), the pose/buffer flow, and the `emit(builder, matrix, x, y, z, r, g, b, a)` helper. Copy these patterns — don't try to share code across files.

- [ ] **Step 1: Create the file**

```kotlin
package dev.nitka.nodewire.client.highlight

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

object BlockHighlightRenderer {

    private val active = ConcurrentHashMap<BlockPos, Long>()

    private object Shards : RenderStateShard("", Runnable {}, Runnable {}) {
        val POSITION_COLOR = POSITION_COLOR_SHADER
        val NO_LIGHTMAP_S = NO_LIGHTMAP
        val TRANSLUCENT = TRANSLUCENT_TRANSPARENCY
        val NO_CULL_S = NO_CULL
        val NO_DEPTH = NO_DEPTH_TEST
        val COLOR_DEPTH = COLOR_DEPTH_WRITE
    }

    private val HIGHLIGHT_TYPE: RenderType = RenderType.create(
        "nodewire_highlight",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(Shards.POSITION_COLOR)
            .setLightmapState(Shards.NO_LIGHTMAP_S)
            .setTransparencyState(Shards.TRANSLUCENT)
            .setCullState(Shards.NO_CULL_S)
            .setDepthTestState(Shards.NO_DEPTH)
            .setWriteMaskState(Shards.COLOR_DEPTH)
            .createCompositeState(false),
    )

    fun highlight(pos: BlockPos, durationMs: Long = DEFAULT_DURATION_MS) {
        active[pos] = System.currentTimeMillis() + durationMs
    }

    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val now = System.currentTimeMillis()
        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value <= now) iter.remove()
        }
        if (active.isEmpty()) return

        val mc = Minecraft.getInstance()
        val cameraPos = event.camera.position
        val pose = event.poseStack
        val bufferSource = mc.renderBuffers().bufferSource()
        val builder = bufferSource.getBuffer(HIGHLIGHT_TYPE)

        // Pulse alpha 0.2..1.0 with ~2 Hz.
        val pulse = (0.6 + 0.4 * sin(now * (2 * PI / PULSE_PERIOD_MS))).toFloat()
        val a = (255 * pulse).toInt().coerceIn(0, 255)
        val r = 0xFF
        val g = 0xE0
        val b = 0x66

        pose.pushPose()
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val matrix = pose.last().pose()

        for (pos in active.keys) {
            drawCubeFrame(builder, matrix, pos, r, g, b, a)
        }

        pose.popPose()
        bufferSource.endBatch(HIGHLIGHT_TYPE)
    }

    private fun drawCubeFrame(
        builder: VertexConsumer,
        matrix: Matrix4f,
        pos: BlockPos,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val lo = -OUTSET
        val hi = 1.0 + OUTSET
        val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()
        // 12 edges of the cube. Each edge runs along one axis between two
        // corners. Encode as (from, to) pairs.
        val edges = arrayOf(
            // Bottom square (y = lo)
            Triple(doubleArrayOf(lo, lo, lo), doubleArrayOf(hi, lo, lo), 0),
            Triple(doubleArrayOf(hi, lo, lo), doubleArrayOf(hi, lo, hi), 2),
            Triple(doubleArrayOf(hi, lo, hi), doubleArrayOf(lo, lo, hi), 0),
            Triple(doubleArrayOf(lo, lo, hi), doubleArrayOf(lo, lo, lo), 2),
            // Top square (y = hi)
            Triple(doubleArrayOf(lo, hi, lo), doubleArrayOf(hi, hi, lo), 0),
            Triple(doubleArrayOf(hi, hi, lo), doubleArrayOf(hi, hi, hi), 2),
            Triple(doubleArrayOf(hi, hi, hi), doubleArrayOf(lo, hi, hi), 0),
            Triple(doubleArrayOf(lo, hi, hi), doubleArrayOf(lo, hi, lo), 2),
            // Vertical edges
            Triple(doubleArrayOf(lo, lo, lo), doubleArrayOf(lo, hi, lo), 1),
            Triple(doubleArrayOf(hi, lo, lo), doubleArrayOf(hi, hi, lo), 1),
            Triple(doubleArrayOf(hi, lo, hi), doubleArrayOf(hi, hi, hi), 1),
            Triple(doubleArrayOf(lo, lo, hi), doubleArrayOf(lo, hi, hi), 1),
        )
        for ((from, to, axis) in edges) {
            // Build a thin quad from `from` to `to` perpendicular to one of
            // the two non-axis directions. Use one orthogonal vector — quads
            // are flat so we only see them from one side, but NO_CULL means
            // both sides render. Picking a single perpendicular keeps the
            // line readable without doubling vertex count.
            emitEdgeQuad(builder, matrix,
                x + from[0], y + from[1], z + from[2],
                x + to[0],   y + to[1],   z + to[2],
                axis, r, g, b, a)
        }
    }

    /**
     * Draws a flat thin quad between two world-space points. `axis` indicates
     * which world axis the edge runs along (0=X, 1=Y, 2=Z) so we can pick a
     * sensible perpendicular for the quad's width.
     */
    private fun emitEdgeQuad(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        axis: Int,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val w = EDGE_THICKNESS * 0.5
        // Perpendicular vector for the quad width.
        val (dx, dy, dz) = when (axis) {
            0 -> Triple(0.0, w, 0.0) // X edge → spread along Y
            1 -> Triple(w, 0.0, 0.0) // Y edge → spread along X
            else -> Triple(w, 0.0, 0.0) // Z edge → spread along X
        }
        emit(builder, matrix, x1 - dx, y1 - dy, z1 - dz, r, g, b, a)
        emit(builder, matrix, x2 - dx, y2 - dy, z2 - dz, r, g, b, a)
        emit(builder, matrix, x2 + dx, y2 + dy, z2 + dz, r, g, b, a)
        emit(builder, matrix, x1 + dx, y1 + dy, z1 + dz, r, g, b, a)
    }

    private fun emit(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Double, y: Double, z: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        builder.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(r, g, b, a)
            .endVertex()
    }

    private const val DEFAULT_DURATION_MS = 3000L
    private const val EDGE_THICKNESS = 0.04
    private const val OUTSET = 0.02
    private const val PULSE_PERIOD_MS = 500.0
}
```

If `Triple(DoubleArray, DoubleArray, Int)` destructuring doesn't compile cleanly (it usually does for `Triple`), fall back to indexed access on a list of records.

- [ ] **Step 2: Build to confirm compile**

Run: `./gradlew compileKotlin` or `./gradlew build` — must succeed. No tests yet, just compile.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/highlight/BlockHighlightRenderer.kt
git commit -m "$(cat <<'EOF'
feat(highlight): BlockHighlightRenderer with pulsing wireframe cube

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `HighlightCommand`

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt`

- [ ] **Step 1: Create the file**

```kotlin
package dev.nitka.nodewire.client.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraftforge.client.event.RegisterClientCommandsEvent

object HighlightCommand {
    fun register(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("nodewire").then(
                Commands.literal("highlight").then(
                    Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes { ctx ->
                            val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                            BlockHighlightRenderer.highlight(pos)
                            1
                        }
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes { ctx ->
                                    val pos = BlockPosArgument.getBlockPos(ctx, "pos")
                                    val secs = IntegerArgumentType.getInteger(ctx, "seconds")
                                    BlockHighlightRenderer.highlight(pos, secs * 1000L)
                                    1
                                },
                        ),
                ),
            ),
        )
    }
}
```

If `event.dispatcher` doesn't resolve, the correct property in Forge 1.20.1 is `event.dispatcher` (verified from Forge sources). If not, fall back to `event.dispatcher` via the Java getter `getDispatcher()`.

- [ ] **Step 2: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/command/HighlightCommand.kt
git commit -m "$(cat <<'EOF'
feat(highlight): /nodewire highlight command

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Wire renderer + command into NodewireClient

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/NodewireClient.kt`

**Context:** Open the file first. The existing `WireWorldRenderer::render` is subscribed via `FORGE_BUS.addListener<RenderLevelStageEvent>(...)`. The command registration goes on `MOD_BUS` per Forge convention for `RegisterClientCommandsEvent`. **Check the existing file** — `RegisterClientCommandsEvent` is FORGE_BUS in newer Forge but MOD_BUS in some older versions; mirror what the file does for similar registry events (e.g. `RegisterKeyMappingsEvent` is MOD_BUS). Use `RegisterClientCommandsEvent` on FORGE_BUS — it's a runtime event, not a mod-init event.

- [ ] **Step 1: Add the two subscriptions**

In `NodewireClient.registerOnModBus(modBus)` (or wherever subscriptions live), add:

```kotlin
FORGE_BUS.addListener<RenderLevelStageEvent>(BlockHighlightRenderer::onRender)
FORGE_BUS.addListener<RegisterClientCommandsEvent>(HighlightCommand::register)
```

Imports:
```kotlin
import dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
import dev.nitka.nodewire.client.command.HighlightCommand
import net.minecraftforge.client.event.RegisterClientCommandsEvent
```

(If `FORGE_BUS` is shadowed or imported differently — match the existing style.)

- [ ] **Step 2: Build, commit**

```bash
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/client/NodewireClient.kt
git commit -m "$(cat <<'EOF'
feat(highlight): wire renderer + command into client event bus

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: BindingsManagerScreen highlight button + chat message

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt`

**Context:** Read `TargetRow` (lines ~200-241). It currently takes `description`, `kindChip`, `onRemove` lambda. Both call sites (channel binding row and side binding row inside `Panel()`) need the target's `BlockPos` to call from.

- [ ] **Step 1: Add `targetPos` parameter to `TargetRow`**

Change the function signature to:

```kotlin
@Composable
private fun TargetRow(
    description: String,
    kindChip: String,
    targetPos: net.minecraft.core.BlockPos,
    onRemove: () -> Unit,
) {
```

- [ ] **Step 2: Insert highlight button before the `×` button**

Inside the row, between the kind chip `Box` and the existing remove `Button`:

```kotlin
Button(
    onClick = {
        dev.nitka.nodewire.client.highlight.BlockHighlightRenderer.highlight(targetPos)
        postHighlightChatMessage(targetPos)
    },
    style = ButtonDefaults.primary().copy(
        padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
    ),
) {
    Text("◎", style = NwTheme.typography.caption)
}
```

If `ButtonDefaults.primary()` doesn't exist, use whatever the neutral button preset is (read the existing `ButtonDefaults` to confirm names).

- [ ] **Step 3: Add `postHighlightChatMessage` helper**

Top-level private function (outside the class, near `sideGlyph`):

```kotlin
private fun postHighlightChatMessage(pos: net.minecraft.core.BlockPos) {
    val command = "/nodewire highlight ${pos.x} ${pos.y} ${pos.z}"
    val component = net.minecraft.network.chat.Component
        .literal("Highlight (${pos.x}, ${pos.y}, ${pos.z}) again")
        .withStyle { style ->
            style
                .withColor(net.minecraft.ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(
                    net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                        command,
                    ),
                )
                .withHoverEvent(
                    net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        net.minecraft.network.chat.Component.literal("Click to re-highlight"),
                    ),
                )
        }
    net.minecraft.client.Minecraft.getInstance().player?.displayClientMessage(component, false)
}
```

- [ ] **Step 4: Update the two `TargetRow` call sites**

Pass `targetPos = b.targetPos` for the channel binding row (line ~134) and `targetPos = sb.targetPos` for the side binding row (line ~151).

- [ ] **Step 5: Build**

`./gradlew build` BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/BindingsManagerScreen.kt
git commit -m "$(cat <<'EOF'
feat(highlight): BindingsManagerScreen highlight button + clickable chat message

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Hand-off smoke test

1. `/nodewire highlight 10 64 10 5` → block at (10, 64, 10) pulses yellow for 5s.
2. `/nodewire highlight ~ ~ ~` → block at player position pulses.
3. In a logic block's BindingsManagerScreen, click `◎` next to a binding → target pulses + chat shows clickable yellow "Highlight (X, Y, Z) again". Click → pulses again.
4. Pulse visibly oscillates alpha at ~2Hz; visible through walls.
5. Hover the chat link → tooltip "Click to re-highlight".
