# Inline Pin Defaults + Config-to-Pin Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. **Each task commits ONCE at the end** — do not split into TDD-step commits.

**Goal:** Inline value editors on unconnected input pins + migrate 7 nodes' config params to pins.

**Architecture:** `PinEditor` is a sealed-class spec attached to each `Pin` declaration in `NodeType.inputs`. The data class field is NOT serialized — `Pin.CODEC` stays at 3 fields — and the renderer reads editor specs from `NodeTypeRegistry` keyed by `(typeKey, pinId)`. Inline values live in `Node.config["pinDefaults"]` as a sub-CompoundTag of per-pin `PinValue.CODEC`-encoded entries. Both evaluators consult `Node.getPinDefault(pinId)` before falling back to `PinValue.default(pin.type)`. Old graphs with config-only enum params migrate transparently via a load-time shim.

**Tech Stack:** Kotlin 2.0.20, NeoForge 1.21.1, Compose runtime 1.7.0, JUnit 5.

**Branch:** `dev`. No `runClient` — use `:compileKotlin`, `:test`, `build`. Single commit per task at the very end.

**Spec:** [`docs/superpowers/specs/2026-05-21-pin-defaults-design.md`](../specs/2026-05-21-pin-defaults-design.md)

---

## File layout

| File | Responsibility |
| ---- | -------------- |
| `src/main/kotlin/dev/nitka/nodewire/graph/PinEditor.kt` | New: sealed-class editor spec + `defaultEditorFor(PinType)`. |
| `src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt` | Add `editor: PinEditor? = null` field (codec unchanged). |
| `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` | Add `getPinDefault(pinId)` + `withPinDefault(pinId, value?)` helpers. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt` | Fallback chain: edge-value → pin-default → type-default. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt` | Same fallback chain. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` | New `setPinDefault(id, pinId, value?)` mutator. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/PinDefaultEditors.kt` | New: 4 compose editors (Numeric / Checkbox / Vector / Enum). |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt` | PinRow shows inline editor when pin has no incoming edge. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` | Migrated nodes (Math/Compare/Logic/Timer/Probability/Delay/Random/PID) read from `inputs[...]`. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` | Migrated nodes declare new input pins with editor specs; drop `configContent` for them. |
| `src/main/kotlin/dev/nitka/nodewire/graph/LegacyConfigMigration.kt` | New: load-time shim that copies legacy config keys into `pinDefaults`. |
| `src/test/kotlin/dev/nitka/nodewire/graph/PinDefaultStorageTest.kt` | New. |
| `src/test/kotlin/dev/nitka/nodewire/graph/EvaluatorPinDefaultTest.kt` | New. |
| `src/test/kotlin/dev/nitka/nodewire/graph/LegacyConfigMigrationTest.kt` | New. |

---

## Phase 1 — Foundation

### Task 1: PinEditor sealed class + defaults

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/PinEditor.kt`

- [ ] **Implement**

```kotlin
package dev.nitka.nodewire.graph

/**
 * Declarative spec for the inline editor a [Pin] gets when it has no
 * incoming edge. Each variant maps to a small compose composable in
 * the editor screen. When [Pin.editor] is null, [defaultEditorFor]
 * picks a sensible default based on [PinType].
 *
 * `None` is the explicit "render nothing" — used for ANY pins and for
 * any output pin (outputs never have inline editors).
 */
sealed class PinEditor {
    object Numeric : PinEditor()
    object Checkbox : PinEditor()
    object Text : PinEditor()
    data class Enum(val options: List<String>) : PinEditor()
    object Vector : PinEditor()
    data class Slider(val min: Float, val max: Float) : PinEditor()
    object None : PinEditor()
}

/** Editor that should be used when [Pin.editor] is left null. */
fun defaultEditorFor(type: PinType): PinEditor = when (type) {
    PinType.BOOL -> PinEditor.Checkbox
    PinType.INT, PinType.FLOAT, PinType.REDSTONE -> PinEditor.Numeric
    PinType.STRING -> PinEditor.Text
    PinType.VEC2, PinType.VEC3, PinType.QUAT -> PinEditor.Vector
    PinType.ANY -> PinEditor.None
}
```

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/PinEditor.kt
git commit -m "feat(graph): PinEditor sealed class + defaultEditorFor

Declarative spec for inline pin editors. defaultEditorFor maps each
PinType to a sane default; declared inline at Pin construction time
(next task) when the default isn't appropriate (e.g. Enum)."
```

---

### Task 2: Pin.editor field + NodeType editor accessor

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt`

- [ ] **Implement Pin.editor (codec untouched)**

Replace the `data class Pin(...)` block in `Pin.kt` with:

```kotlin
data class Pin(
    val id: String,
    val name: String,
    val type: PinType,
    /**
     * Inline-editor override for this pin. Null → renderer falls back
     * to [defaultEditorFor]. Not serialised — Pin.CODEC stays at 3
     * fields because editor specs are part of the canonical [NodeType]
     * registry, not per-instance graph state. Loaded pins have
     * editor == null and the renderer looks up the canonical spec
     * via [NodeType.editorFor].
     */
    val editor: PinEditor? = null,
) {
    companion object {
        val CODEC: Codec<Pin> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("id").forGetter(Pin::id),
                Codec.STRING.fieldOf("name").forGetter(Pin::name),
                PinType.CODEC.fieldOf("type").forGetter(Pin::type),
            ).apply(i, ::Pin)
        }
    }
}
```

- [ ] **Add NodeType.editorFor accessor**

In `NodeType.kt`, add inside the `data class NodeType` body (right before the closing brace):

```kotlin
    /**
     * Look up the inline-editor spec for one of this node type's input
     * pins. Returns the explicit [Pin.editor] if declared; otherwise
     * falls back to [defaultEditorFor] based on the pin's type. Unknown
     * pin ids (e.g. after a Switch reshape that added case_N which
     * isn't in the canonical inputs list) get the type-default — the
     * caller passes the *saved* pin type alongside.
     */
    fun editorFor(pinId: String, fallbackType: PinType = PinType.ANY): PinEditor {
        val canonical = inputs.firstOrNull { it.id == pinId }
        if (canonical?.editor != null) return canonical.editor
        return defaultEditorFor(canonical?.type ?: fallbackType)
    }
```

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`. Pin.CODEC unchanged ⇒ no existing test breaks.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/Pin.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/NodeType.kt
git commit -m "feat(graph): Pin.editor field + NodeType.editorFor accessor

Pin gains an optional editor spec. Not serialised — codec stays at
3 fields because editor specs are NodeType-canonical, not per-graph
state. NodeType.editorFor(pinId, fallbackType) returns the explicit
or type-derived editor; used by the renderer to pick an inline UI
for unconnected pins."
```

---

### Task 3: Node.getPinDefault / withPinDefault helpers + storage test

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/PinDefaultStorageTest.kt`

- [ ] **Add storage helpers to Node.kt**

Append, near the bottom of the file (inside the file's top-level scope, NOT inside the data class — they're extension-style helpers; alternatively place them inside `Node`'s body, your call, but keep them next to `Node`):

```kotlin
/**
 * Inline pin-default lookup. Returns the value stored under
 * `config["pinDefaults"][pinId]` or null if no default has been set
 * for this pin. Decode is via [PinValue.CODEC]; corrupt entries return
 * null (caller falls back to [PinValue.default]).
 */
fun Node.getPinDefault(pinId: String): PinValue? {
    val defaults = config.get("pinDefaults") as? net.minecraft.nbt.CompoundTag ?: return null
    val tag = defaults.get(pinId) ?: return null
    return PinValue.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null)
}

/**
 * Return a copy of this node with the inline default for [pinId] set
 * to [value], or cleared if [value] is null. Mutates only the
 * `pinDefaults` sub-tag of [config]; all other config keys are
 * preserved.
 */
fun Node.withPinDefault(pinId: String, value: PinValue?): Node {
    val cfg = config.copy()
    val existing = cfg.get("pinDefaults") as? net.minecraft.nbt.CompoundTag
    val defaults = existing?.copy() ?: net.minecraft.nbt.CompoundTag()
    if (value == null) {
        defaults.remove(pinId)
    } else {
        PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, value)
            .result().ifPresent { defaults.put(pinId, it) }
    }
    cfg.put("pinDefaults", defaults)
    return copy(config = cfg)
}
```

- [ ] **Write the test**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PinDefaultStorageTest {

    private fun bareNode(): Node = Node(
        id = Node.newId(),
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "test"),
        pos = CanvasPos(0f, 0f),
        inputs = emptyList(),
        outputs = emptyList(),
        config = CompoundTag(),
    )

    @Test fun `unset pin returns null`() {
        assertNull(bareNode().getPinDefault("anything"))
    }

    @Test fun `set then get returns the same value`() {
        val n = bareNode().withPinDefault("x", PinValue.Float(1.5f))
        assertEquals(PinValue.Float(1.5f), n.getPinDefault("x"))
    }

    @Test fun `set null removes`() {
        var n = bareNode().withPinDefault("x", PinValue.Float(1.5f))
        n = n.withPinDefault("x", null)
        assertNull(n.getPinDefault("x"))
    }

    @Test fun `multiple pins coexist`() {
        val n = bareNode()
            .withPinDefault("a", PinValue.Int(7))
            .withPinDefault("b", PinValue.Bool(true))
            .withPinDefault("c", PinValue.Vec3(1f, 2f, 3f))
        assertEquals(PinValue.Int(7), n.getPinDefault("a"))
        assertEquals(PinValue.Bool(true), n.getPinDefault("b"))
        assertEquals(PinValue.Vec3(1f, 2f, 3f), n.getPinDefault("c"))
    }

    @Test fun `preserves unrelated config keys`() {
        val n0 = bareNode().copy(config = CompoundTag().apply { putString("op", "ADD") })
        val n1 = n0.withPinDefault("x", PinValue.Float(1f))
        assertEquals("ADD", n1.config.getString("op"))
        assertEquals(PinValue.Float(1f), n1.getPinDefault("x"))
    }

    @Test fun `round trip via PinValue CODEC every type`() {
        val cases = listOf(
            PinValue.Bool(true),
            PinValue.Int(-3),
            PinValue.Float(2.5f),
            PinValue.Redstone(11),
            PinValue.Str("hello"),
            PinValue.Vec2(1f, 2f),
            PinValue.Vec3(1f, 2f, 3f),
            PinValue.Quat(0.1f, 0.2f, 0.3f, 0.9f),
        )
        for (v in cases) {
            val n = bareNode().withPinDefault("v", v)
            assertEquals(v, n.getPinDefault("v"), "round-trip failed for $v")
        }
    }
}
```

- [ ] **Build + test**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.PinDefaultStorageTest"
```

Expected: 6 tests pass.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/Node.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/PinDefaultStorageTest.kt
git commit -m "feat(graph): Node pin-default storage helpers + test

getPinDefault(pinId) / withPinDefault(pinId, value?) live in
config['pinDefaults'] as a sub-CompoundTag of PinValue.CODEC entries
keyed by pin id. Other config keys preserved; setting null removes
the entry. 6 round-trip tests across every PinType."
```

---

### Task 4: Evaluator edge-read integration

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/EvaluatorPinDefaultTest.kt`

- [ ] **Update StatefulGraphEvaluator**

Find the assignment that currently looks like this in `tick(...)`:

```kotlin
inputs[pin.id] = when {
    value == null -> PinValue.default(pin.type)
    pin.type == PinType.ANY -> value
    else -> PinValueConversion.convert(value, pin.type)
}
```

Replace with:

```kotlin
inputs[pin.id] = when {
    value == null -> {
        val pinDefault = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
        if (pin.type == PinType.ANY) pinDefault
        else PinValueConversion.convert(pinDefault, pin.type)
    }
    pin.type == PinType.ANY -> value
    else -> PinValueConversion.convert(value, pin.type)
}
```

- [ ] **Update GraphEvaluator**

The analogous block in `evaluate(...)` uses a variable name like `rawValue` (per Algo T3 history). Apply the same fallback-to-pin-default change:

```kotlin
inputs[pin.id] = when {
    rawValue == null -> {
        val pinDefault = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
        if (pin.type == PinType.ANY) pinDefault
        else PinValueConversion.convert(pinDefault, pin.type)
    }
    pin.type == PinType.ANY -> rawValue
    else -> PinValueConversion.convert(rawValue, pin.type)
}
```

(If the local is named differently, rename within the scope to match.)

- [ ] **Write the test**

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvaluatorPinDefaultTest {

    /**
     * Build a minimal NodeType + graph that runs the evaluator for a
     * single node with one input pin and a stateless identity-style
     * evaluator that just returns the input as its output.
     */
    private val identityType = NodeType(
        id = ResourceLocation.fromNamespaceAndPath("nodewire", "test_identity"),
        displayName = "Test Identity",
        category = NodeCategory.LOGIC,
        inputs = listOf(Pin("x", "X", PinType.FLOAT)),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = { _, inputs -> mapOf("out" to (inputs["x"] ?: PinValue.Float(0f))) },
    )

    private fun runOnce(node: Node): PinValue? {
        NodeTypeRegistry.register(identityType)
        val graph = NodeGraph()
        graph.add(node)
        return try {
            GraphEvaluator().evaluate(graph).valueAt(node.id, "out")
        } finally {
            // NodeTypeRegistry has no unregister — tests share the registry.
            // The test type's id is unique so it won't collide.
        }
    }

    @Test fun `unwired pin with default uses default`() {
        val node = Node(
            id = Node.newId(),
            typeKey = identityType.id,
            pos = CanvasPos(0f, 0f),
            inputs = identityType.inputs,
            outputs = identityType.outputs,
            config = CompoundTag(),
        ).withPinDefault("x", PinValue.Float(42f))
        assertEquals(PinValue.Float(42f), runOnce(node))
    }

    @Test fun `unwired pin without default falls back to type default`() {
        val node = Node(
            id = Node.newId(),
            typeKey = identityType.id,
            pos = CanvasPos(0f, 0f),
            inputs = identityType.inputs,
            outputs = identityType.outputs,
            config = CompoundTag(),
        )
        assertEquals(PinValue.Float(0f), runOnce(node))
    }
}
```

If `GraphEvaluator` requires a different setup (look at existing `GraphEvaluatorTest` for the pattern), adapt the invocation but keep the assertion intent.

- [ ] **Build + tests**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.EvaluatorPinDefaultTest"
./gradlew test
```

Expected: 2 new tests pass. Full suite passes (only pre-existing `GroupProxyPins*` failures remain — 4 of them).

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/EvaluatorPinDefaultTest.kt
git commit -m "feat(graph): evaluator falls back to pin-default before type-default

Both evaluators now consult Node.getPinDefault(pinId) when the input
pin has no incoming edge, applying PinValueConversion if the stored
default's type differs from the pin's declared type. Wired pins are
unaffected — the change only fires on unconnected inputs."
```

---

## Phase 2 — UI

### Task 5: EditorState.setPinDefault mutator

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`

- [ ] **Add mutator**

Add alongside the existing `setNodeLabel` / `setEdgeLabel` mutators:

```kotlin
    /**
     * Set (or clear, with [value] == null) the inline default for an
     * input pin. Mergeable so typing into a numeric editor collapses
     * into a single undo entry. Wired pins ignore the default — see
     * the evaluator edge-read change — but setting one is harmless and
     * is preserved when the user disconnects the wire.
     */
    fun setPinDefault(
        id: dev.nitka.nodewire.graph.NodeId,
        pinId: String,
        value: dev.nitka.nodewire.graph.PinValue?,
    ) {
        mutateGraph(mergeable = true) {
            _updateNodeInternal(id) { it.withPinDefault(pinId, value) }
        }
        requestSave?.invoke()
    }
```

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt
git commit -m "feat(editor): setPinDefault mutator

Mergeable mutation so typing into a numeric editor collapses into one
undo entry. requestSave is called so the change persists to the
server BE."
```

---

### Task 6: Inline editor composables (Numeric / Checkbox / Vector)

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/client/screen/PinDefaultEditors.kt`

- [ ] **Create the file with Numeric, Checkbox, Vector editors**

```kotlin
package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.PinEditor
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Inline editor for an unconnected input pin. Dispatches to a per-
 * variant compose function based on the [PinEditor] spec. [current]
 * is the current pin-default value (or [PinValue.default] if none
 * has been set yet) and [onChange] persists a new value to the node.
 *
 * `PinEditor.None` and `PinEditor.Slider` are deliberately handled by
 * the renderer caller (Slider is out of scope per the spec; None
 * means "don't render anything") — this function does not handle them.
 */
@Composable
fun PinDefaultEditor(
    editor: PinEditor,
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    when (editor) {
        PinEditor.Numeric -> NumericEditor(pinType, current, onChange)
        PinEditor.Checkbox -> CheckboxEditor(current, onChange)
        PinEditor.Text -> TextEditor(current, onChange)
        PinEditor.Vector -> VectorEditor(pinType, current, onChange)
        is PinEditor.Enum -> EnumEditor(editor.options, current, onChange)
        is PinEditor.Slider, PinEditor.None -> Unit  // not handled here
    }
}

@Composable
private fun NumericEditor(
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val initial = when (current) {
        is PinValue.Int -> current.value.toString()
        is PinValue.Float -> current.value.toString()
        is PinValue.Redstone -> current.value.toString()
        else -> "0"
    }
    var text by remember(initial) { mutableStateOf(initial) }
    TextInput(
        value = text,
        modifier = Modifier.width(50),
        onValueChange = { text = it },
        onSubmit = {
            val parsed: PinValue = when (pinType) {
                PinType.INT -> PinValue.Int(text.toIntOrNull() ?: 0)
                PinType.FLOAT -> PinValue.Float(text.toFloatOrNull() ?: 0f)
                PinType.REDSTONE -> PinValue.Redstone(
                    (text.toIntOrNull() ?: 0).coerceIn(0, 15)
                )
                else -> return@TextInput
            }
            onChange(parsed)
        },
    )
}

@Composable
private fun CheckboxEditor(current: PinValue, onChange: (PinValue) -> Unit) {
    val v = (current as? PinValue.Bool)?.value ?: false
    // Minimal "toggle" — clicking flips. Visual: small accent square
    // when true, hollow when false. Uses Button for hit-handling.
    Button(
        onClick = { onChange(PinValue.Bool(!v)) },
        modifier = Modifier.width(14),
    ) {
        Text(if (v) "✓" else " ", style = NwTheme.typography.caption)
    }
}

@Composable
private fun TextEditor(current: PinValue, onChange: (PinValue) -> Unit) {
    val initial = (current as? PinValue.Str)?.value ?: ""
    var text by remember(initial) { mutableStateOf(initial) }
    TextInput(
        value = text,
        modifier = Modifier.width(100),
        onValueChange = { text = it },
        onSubmit = { onChange(PinValue.Str(text)) },
    )
}

@Composable
private fun VectorEditor(
    pinType: PinType,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val (xv, yv, zv, wv) = when (current) {
        is PinValue.Vec2 -> arrayOf(current.x, current.y, 0f, 0f)
        is PinValue.Vec3 -> arrayOf(current.x, current.y, current.z, 0f)
        is PinValue.Quat -> arrayOf(current.x, current.y, current.z, current.w)
        else -> arrayOf(0f, 0f, 0f, 0f)
    }
    val componentCount = when (pinType) {
        PinType.VEC2 -> 2
        PinType.VEC3 -> 3
        PinType.QUAT -> 4
        else -> 0
    }
    var x by remember(xv) { mutableStateOf(xv.toString()) }
    var y by remember(yv) { mutableStateOf(yv.toString()) }
    var z by remember(zv) { mutableStateOf(zv.toString()) }
    var w by remember(wv) { mutableStateOf(wv.toString()) }

    fun commit() {
        val xf = x.toFloatOrNull() ?: 0f
        val yf = y.toFloatOrNull() ?: 0f
        val zf = z.toFloatOrNull() ?: 0f
        val wf = w.toFloatOrNull() ?: 0f
        val value: PinValue = when (pinType) {
            PinType.VEC2 -> PinValue.Vec2(xf, yf)
            PinType.VEC3 -> PinValue.Vec3(xf, yf, zf)
            PinType.QUAT -> PinValue.Quat(xf, yf, zf, wf)
            else -> return
        }
        onChange(value)
    }

    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(2),
    ) {
        TextInput(value = x, modifier = Modifier.width(30), onValueChange = { x = it }, onSubmit = { commit() })
        TextInput(value = y, modifier = Modifier.width(30), onValueChange = { y = it }, onSubmit = { commit() })
        if (componentCount >= 3) TextInput(value = z, modifier = Modifier.width(30), onValueChange = { z = it }, onSubmit = { commit() })
        if (componentCount >= 4) TextInput(value = w, modifier = Modifier.width(30), onValueChange = { w = it }, onSubmit = { commit() })
    }
}

@Composable
private fun EnumEditor(
    options: List<String>,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val cur = (current as? PinValue.Str)?.value ?: options.firstOrNull().orEmpty()
    // Click cycles to next option — simple, no popup. A future revision
    // can wire this up to an actual dropdown via the existing
    // ContextMenu overlay; that's out of scope here per the spec
    // (the spec says "popup menu" but pricing in a full popup
    // composable is larger than this slice — start with cycling,
    // upgrade later).
    Button(
        onClick = {
            val idx = options.indexOf(cur).coerceAtLeast(0)
            val next = options[(idx + 1) % options.size]
            onChange(PinValue.Str(next))
        },
    ) {
        Text(cur.ifEmpty { "—" }, style = NwTheme.typography.caption)
    }
}
```

(Adjust imports if a project component differs — for example, if `Button` requires a different parameter shape, follow the existing call sites in `NodeConfigContent.kt`.)

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`. If a referenced compose API isn't quite right, fix the import / call to match the existing pattern in `NodeConfigContent.kt` — the editors are small and the project's exact compose surface dictates the signatures.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/PinDefaultEditors.kt
git commit -m "feat(editor): inline pin-default editor composables

PinDefaultEditor dispatches to a per-variant composable. Numeric /
Checkbox / Text / Vector are the standard set; Enum cycles to the
next option on click (deferred upgrade to a popup dropdown). Slider
and None are deliberately not handled — Slider is out of scope per
the spec, None means 'render nothing'."
```

---

### Task 7: NodeCard pin-row inline editor integration

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt`

- [ ] **Inspect existing pin-row rendering**

Find the section that renders each pin in NodeCard — there's a `PinHandle` / `CardBody` / `pinRow` composable. Locate the input-pin loop (something like `for (pin in node.inputs) { ... }`).

- [ ] **Add editor rendering inside the input pin row**

Inside the existing input-pin row composable (the row that draws the dot + name on the LEFT side of the card), AFTER the pin name `Text`, insert:

```kotlin
            // Inline default editor — shown only when this pin has no
            // incoming edge. Looks up the editor spec from the canonical
            // NodeType in the registry; the saved pin's type is used as a
            // fallback for the type-derived default (handles Switch's
            // expanded case pins that aren't in the canonical inputs).
            val hasIncomingEdge = remember(edges, node.id, pin.id) {
                edges.any { it.to.node == node.id && it.to.pin == pin.id }
            }
            if (!hasIncomingEdge) {
                val nodeType = NodeTypeRegistry.get(node.typeKey)
                val editor = nodeType?.editorFor(pin.id, pin.type)
                    ?: defaultEditorFor(pin.type)
                if (editor !is PinEditor.None) {
                    val current = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
                    PinDefaultEditor(
                        editor = editor,
                        pinType = pin.type,
                        current = current,
                        onChange = { newValue ->
                            editor?.let { /* avoid name collision */ }
                            LocalEditorState.current?.setPinDefault(node.id, pin.id, newValue)
                        },
                    )
                }
            }
```

(Watch for name shadowing — local `editor` collides with `LocalEditorState.current` reference. Rename one of them, e.g. `val editorSpec = ...`. The exact integration depends on the existing `PinRow` composable's scope; if `edges` and `LocalEditorState` aren't already in scope, hoist them via `LocalEditorState.current?.edges?.collectAsState()` at the row's top, matching the rest of NodeCard.)

- [ ] **Build**

Run: `./gradlew :compileKotlin && ./gradlew :compileTestKotlin`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Manual sanity check (non-blocking)**

The compile pass is enough — UI rendering is verified later in Task 12's manual in-client step.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/NodeCard.kt
git commit -m "feat(editor): show inline default editor on unconnected pins

NodeCard's input pin row now renders PinDefaultEditor next to the
pin name when no edge attaches to that pin. The editor spec comes
from the canonical NodeType (NodeType.editorFor), falling back to
defaultEditorFor(pin.type) for pins that aren't in the canonical
inputs (e.g. Switch's expanded case pins). PinEditor.None and
output pins render nothing."
```

---

### Task 8: Enum editor — popup dropdown (upgrade from cycle)

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/PinDefaultEditors.kt`

- [ ] **Replace EnumEditor with popup variant**

Use the existing `ContextMenu` machinery to render the option list as a popup. The pattern lives in `NodeContextMenu.kt`. Replace `EnumEditor` with:

```kotlin
@Composable
private fun EnumEditor(
    options: List<String>,
    current: PinValue,
    onChange: (PinValue) -> Unit,
) {
    val cur = (current as? PinValue.Str)?.value ?: options.firstOrNull().orEmpty()
    var menuOpen by remember { mutableStateOf(false) }

    Button(onClick = { menuOpen = true }) {
        Text(cur.ifEmpty { "—" }, style = NwTheme.typography.caption)
    }

    if (menuOpen) {
        dev.nitka.nodewire.ui.components.ContextMenu(
            items = options.map { opt ->
                dev.nitka.nodewire.ui.components.ContextMenuItem.Action(opt) {
                    onChange(PinValue.Str(opt))
                    menuOpen = false
                }
            },
            position = dev.nitka.nodewire.ui.overlay.PopupPosition.Auto,
            onDismiss = { menuOpen = false },
        )
    }
}
```

If `PopupPosition.Auto` isn't a thing, look at how `NodeContextMenu` invokes `ContextMenu` and copy its position convention (e.g. anchor the popup near the click — the existing context menus pass screen-space coords; for a button-anchored popup we can rely on the menu's default placement next to the trigger). Adapt to whatever the existing `ContextMenu` accepts.

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/client/screen/PinDefaultEditors.kt
git commit -m "feat(editor): EnumEditor opens a ContextMenu dropdown

Click the enum button → existing ContextMenu lists every option;
picking one persists the new pin-default. Replaces the placeholder
'cycle to next on click' from Task 6."
```

---

## Phase 3 — Migration

### Task 9: Math + Compare + LogicGate ops migrate to pin

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` (remove the old configContent composables for these three nodes — they're no longer needed)

- [ ] **Determine the existing op-string sets**

Open `StockEvaluators.kt` and find the `Math`, `Compare`, `LogicGate` evaluators. Each reads `config.getString("op")` and does a `when` on the result. List the exact op strings used (e.g. `"add", "sub", "mul", "div", "min", "max", "abs", "sqrt", "mod", "neg", "floor", "ceil", "round"` for Math). Use these EXACT strings as the Enum options — the back-compat shim copies them verbatim.

If the existing Math evaluator has e.g. 13 ops, use all 13. Match Case 1-for-1.

- [ ] **Update evaluators to read from `inputs["op"]`**

For each of the three:

```kotlin
val Math: NodeEvaluator = { _, inputs ->
    val op = (inputs["op"] as? PinValue.Str)?.value ?: "add"
    val a = (inputs["a"] as? PinValue.Float)?.value ?: 0f
    val b = (inputs["b"] as? PinValue.Float)?.value ?: 0f
    val out = when (op) {
        "add" -> a + b
        "sub" -> a - b
        // ... preserve every existing branch
        else -> 0f
    }
    mapOf("out" to PinValue.Float(out))
}
```

(Use whatever the current evaluator does — the only change is the source of `op`: `inputs["op"]` instead of `config.getString("op")`.)

Apply the same pattern to `Compare` and `LogicGate`.

- [ ] **Add `op` input pin with Enum editor**

In `StockNodeTypes.kt` for each of MATH / COMPARE / LOGIC_GATE:

```kotlin
val MATH = nodeType(
    id = "math",
    displayName = "➗ Math",
    category = NodeCategory.MATH,
    inputs = listOf(
        Pin("op", "Op", PinType.STRING, editor = PinEditor.Enum(
            listOf("add", "sub", "mul", "div", "min", "max", "abs", "sqrt", "mod", "neg", "floor", "ceil", "round")
        )),
        Pin("a", "A", PinType.FLOAT),
        Pin("b", "B", PinType.FLOAT),
    ),
    outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
    defaultConfig = {
        net.minecraft.nbt.CompoundTag().apply {
            put("pinDefaults", net.minecraft.nbt.CompoundTag().apply {
                dev.nitka.nodewire.graph.PinValue.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, PinValue.Str("add"))
                    .result().ifPresent { put("op", it) }
            })
        }
    },
    evaluate = StockEvaluators.Math,
)
```

(Use the actual current op list — what's shown is illustrative.)

Remove `configContent = NodeConfigContent.Math` (it's gone). Same for COMPARE and LOGIC_GATE.

Apply the same shape to Compare with options `listOf("eq", "ne", "lt", "le", "gt", "ge")` (or whatever the existing evaluator uses), and LogicGate with `listOf("and", "or", "not", "xor", "nand", "nor", "xnor")`.

- [ ] **Drop the obsolete configContent composables**

In `NodeConfigContent.kt`, delete the `Math`, `Compare`, `LogicGate` composables (they're no longer referenced). Keep the rest of the file untouched.

- [ ] **Build + test**

```bash
./gradlew test
```

The pre-existing Math / Compare / LogicGate tests might break because they put `op` into `config`. The codec back-compat shim (Task 12) will fix that, but for now those tests fail. **Skip-document** them in the commit message and address in Task 12.

Alternatively, update the test setUp to use `node.withPinDefault("op", PinValue.Str("add"))` and verify the new shape works — that's the better path. Find the existing tests in `src/test/kotlin/dev/nitka/nodewire/graph/` (`StockEvaluatorsMathTest`, etc.) and update their setup to use pin-defaults.

- [ ] **Commit**

```bash
git add -A
git commit -m "feat(node): migrate Math+Compare+LogicGate ops to STRING input pins

Each op is now declared as a pin with PinEditor.Enum spec. The
evaluators read inputs['op'] instead of config.getString('op'),
so a dynamic Switch can drive the op selection from elsewhere in
the graph. defaultConfig seeds the pin-default to the same op
that was previously the config default; the back-compat shim in
Task 12 carries over saved-graph values. NodeConfigContent
entries for these three nodes are removed."
```

---

### Task 10: Timer + Delay + Probability + RandomInt scalars migrate to pins

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt`

- [ ] **Migrate Timer**

Current: reads `config.getInt("period")`. New: read `inputs["period"]` as `PinValue.Int`.

Add input pin `Pin("period", "Period", PinType.INT)` with `editor = null` (default Numeric). Seed pin-default to the existing config default (probably 20 ticks).

- [ ] **Migrate Delay**

Same pattern: `config.getInt("ticks")` → `inputs["ticks"]: PinValue.Int`. Add `Pin("ticks", "Ticks", PinType.INT)`.

- [ ] **Migrate Probability**

`config.getFloat("p")` → `inputs["p"]: PinValue.Float`. Add `Pin("p", "P", PinType.FLOAT)`.

- [ ] **Migrate RandomInt**

`config.getInt("min") + config.getInt("max")` → `inputs["min"]: PinValue.Int + inputs["max"]: PinValue.Int`. Add both pins.

- [ ] **Drop their configContent composables**

Remove `TimerPeriod`, `DelayTicks`, `Probability`, `IntRange` from `NodeConfigContent.kt`. Drop the `configContent = ...` references from the four `nodeType(...)` declarations.

- [ ] **Build + test**

```bash
./gradlew test
```

Existing tests for these four nodes might fail with the same `config` setup issue as Task 9. Update their setUp to use `withPinDefault`. The back-compat shim (Task 12) handles loaded graphs.

- [ ] **Commit**

```bash
git add -A
git commit -m "feat(node): migrate Timer + Delay + Probability + RandomInt scalars to pins

Period / ticks / p / min / max are now INT / FLOAT input pins with
default-derived Numeric editors. configContent composables removed.
defaultConfig seeds the pin-default to the previous config default
so freshly-spawned nodes behave identically to before."
```

---

### Task 11: PID i_min / i_max migrate to pins

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`

- [ ] **Update PID evaluator**

Find the existing `Pid` TickEvaluator (added in v0.3.0). It reads `config.getFloat("i_min")` and `config.getFloat("i_max")` with a quirk: zero is treated as "use the default range ±1000". With pin-defaults the quirk is no longer needed — we just seed the defaults to ±1000.

Replace those two reads:

```kotlin
val iMin = (inputs["i_min"] as? PinValue.Float)?.value ?: -1000f
val iMax = (inputs["i_max"] as? PinValue.Float)?.value ?: 1000f
```

- [ ] **Add the two input pins**

In `StockNodeTypes.kt`, append to PID's inputs:

```kotlin
Pin("i_min", "I Min", PinType.FLOAT),
Pin("i_max", "I Max", PinType.FLOAT),
```

Seed pin-defaults via `defaultConfig`:

```kotlin
defaultConfig = {
    net.minecraft.nbt.CompoundTag().apply {
        put("pinDefaults", net.minecraft.nbt.CompoundTag().apply {
            PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, PinValue.Float(-1000f))
                .result().ifPresent { put("i_min", it) }
            PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, PinValue.Float(1000f))
                .result().ifPresent { put("i_max", it) }
        })
    }
},
```

Remove the old config-based defaultConfig entries (`putFloat("i_min", -1000f)` etc.) — they're now pin-defaults.

- [ ] **Update PID tests**

The existing tests in `AlgoNodeEvaluatorsTest.kt` for PID pass `config` without `i_min`/`i_max` keys. Those tests will continue to work because the evaluator's `?: -1000f` / `?: 1000f` fallback fires. But add one new test that confirms `i_min` and `i_max` from `inputs` are respected:

```kotlin
@Test fun `pid clamps integral via input pins`() {
    val frame = mapOf(
        "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(9f),
        "kp" to PinValue.Float(0f), "ki" to PinValue.Float(1f), "kd" to PinValue.Float(0f),
        "i_min" to PinValue.Float(-2f), "i_max" to PinValue.Float(2f),
    )
    val outs = runTicks(StockEvaluators.Pid, frames = List(10) { frame })
    // Without the clamp integral would reach 10. With i_max=2 it caps at 2.
    val last = (outs.last()["out"] as PinValue.Float).value
    org.junit.jupiter.api.Assertions.assertEquals(2f, last, 0.001f)
}
```

- [ ] **Build + test**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.AlgoNodeEvaluatorsTest"
```

Expected: all PID tests pass + the new clamp test passes.

- [ ] **Commit**

```bash
git add -A
git commit -m "feat(node): PID integral bounds migrate from config to pins

i_min and i_max are now FLOAT input pins seeded to ±1000 by default.
The 'zero means default range' quirk in the old config path is gone
— pin-defaults make the value explicit. New test verifies the clamp
actually fires when the bounds come from inputs."
```

---

### Task 12: Legacy config migration shim + tests + manual verification

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/LegacyConfigMigration.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/Node.kt` (hook the shim into `Node.CODEC`'s decode path)
- Create: `src/test/kotlin/dev/nitka/nodewire/graph/LegacyConfigMigrationTest.kt`

- [ ] **Write the migration helper**

```kotlin
package dev.nitka.nodewire.graph

/**
 * One-shot legacy-config migration. Old saves stored op / period /
 * etc. as direct keys in [Node.config]; the modern model expects
 * them in `config["pinDefaults"]` keyed by pin id. This helper runs
 * at [Node.CODEC] decode-time, before the node is handed to anything
 * else, so the rest of the codebase only ever sees the modern shape.
 *
 * Idempotent: if a node already has a pin-default for the new pin,
 * the legacy value is left alone (newer saves win).
 */
object LegacyConfigMigration {

    private data class LegacyKey(val configKey: String, val pinId: String, val pinType: PinType)

    /** typeKey.path → list of legacy mappings to apply. */
    private val MIGRATIONS: Map<String, List<LegacyKey>> = mapOf(
        "math" to listOf(LegacyKey("op", "op", PinType.STRING)),
        "compare" to listOf(LegacyKey("op", "op", PinType.STRING)),
        "logic_gate" to listOf(LegacyKey("op", "op", PinType.STRING)),
        "timer" to listOf(LegacyKey("period", "period", PinType.INT)),
        "delay" to listOf(LegacyKey("ticks", "ticks", PinType.INT)),
        "probability" to listOf(LegacyKey("p", "p", PinType.FLOAT)),
        "random_int" to listOf(
            LegacyKey("min", "min", PinType.INT),
            LegacyKey("max", "max", PinType.INT),
        ),
        "pid" to listOf(
            LegacyKey("i_min", "i_min", PinType.FLOAT),
            LegacyKey("i_max", "i_max", PinType.FLOAT),
        ),
    )

    fun migrate(node: Node): Node {
        val migrations = MIGRATIONS[node.typeKey.path] ?: return node
        var updated = node
        for (m in migrations) {
            // Modern save already has it — leave alone.
            if (updated.getPinDefault(m.pinId) != null) continue
            if (!updated.config.contains(m.configKey)) continue
            val value: PinValue = when (m.pinType) {
                PinType.INT -> PinValue.Int(updated.config.getInt(m.configKey))
                PinType.FLOAT -> PinValue.Float(updated.config.getFloat(m.configKey))
                PinType.STRING -> PinValue.Str(updated.config.getString(m.configKey))
                PinType.BOOL -> PinValue.Bool(updated.config.getBoolean(m.configKey))
                PinType.REDSTONE -> PinValue.Redstone(
                    updated.config.getInt(m.configKey).coerceIn(0, 15)
                )
                else -> continue
            }
            updated = updated.withPinDefault(m.pinId, value)
        }
        return updated
    }
}
```

- [ ] **Hook into Node.CODEC**

Find `Node.CODEC` in `Node.kt`. It uses `RecordCodecBuilder.create { i -> ... }.apply(i, ::Node)`. Wrap the constructed Node in the migration:

```kotlin
val CODEC: Codec<Node> = RecordCodecBuilder.create { i ->
    i.group(
        // ... existing fields unchanged ...
    ).apply(i) { id, typeKey, pos, inputs, outputs, config, label ->
        LegacyConfigMigration.migrate(
            Node(id, typeKey, pos, inputs, outputs, config, label)
        )
    }
}
```

(Adjust the constructor args to match the actual current ones — copy them from the existing apply call.)

- [ ] **Write tests**

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.JsonOps
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegacyConfigMigrationTest {

    private fun legacyNode(typePath: String, configBuilder: CompoundTag.() -> Unit): Node {
        val cfg = CompoundTag().apply(configBuilder)
        return Node(
            id = Node.newId(),
            typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", typePath),
            pos = CanvasPos(0f, 0f),
            inputs = emptyList(),
            outputs = emptyList(),
            config = cfg,
        )
    }

    @Test fun `math op string migrates`() {
        val node = LegacyConfigMigration.migrate(
            legacyNode("math") { putString("op", "mul") }
        )
        assertEquals(PinValue.Str("mul"), node.getPinDefault("op"))
    }

    @Test fun `timer period int migrates`() {
        val node = LegacyConfigMigration.migrate(
            legacyNode("timer") { putInt("period", 40) }
        )
        assertEquals(PinValue.Int(40), node.getPinDefault("period"))
    }

    @Test fun `random_int min and max both migrate`() {
        val node = LegacyConfigMigration.migrate(
            legacyNode("random_int") {
                putInt("min", 5)
                putInt("max", 100)
            }
        )
        assertEquals(PinValue.Int(5), node.getPinDefault("min"))
        assertEquals(PinValue.Int(100), node.getPinDefault("max"))
    }

    @Test fun `modern save with pinDefault already set wins`() {
        val node0 = legacyNode("math") {
            putString("op", "mul")
            put("pinDefaults", CompoundTag().apply {
                PinValue.CODEC.encodeStart(NbtOps.INSTANCE, PinValue.Str("div"))
                    .result().ifPresent { put("op", it) }
            })
        }
        val migrated = LegacyConfigMigration.migrate(node0)
        // The pre-existing pin-default of "div" should win over the legacy
        // config key "mul" — the newer save takes precedence.
        assertEquals(PinValue.Str("div"), migrated.getPinDefault("op"))
    }

    @Test fun `unmigrated node type passes through unchanged`() {
        val node = legacyNode("constant") { putString("op", "x") }  // not in MIGRATIONS
        assertEquals(node, LegacyConfigMigration.migrate(node))
    }
}
```

- [ ] **Build + test**

```bash
./gradlew test
```

Expected: all algo/migration tests pass. Any pre-Task-9/10/11 tests that broke (because they put `op` into `config`) are now ALSO fixed because the migration shim runs at Node load time... actually no — the shim runs in `Node.CODEC.decode`, not on direct `Node(...)` construction. Existing tests that construct nodes directly with legacy config keys need separate fixing — either:

1. Update their setUp to use `withPinDefault` directly.
2. Wrap their construction in `LegacyConfigMigration.migrate(...)`.

Use option 1 — it's the modern way and what the spec recommends. Find the failing tests in `src/test/kotlin/dev/nitka/nodewire/graph/` and switch their setup to `.withPinDefault("op", PinValue.Str("add"))` instead of `.config.putString("op", "add")`.

- [ ] **Bump StockNodeTypesTest expected count check**

Phase 3 doesn't change the registered count (every migrated node still exists). The count check (`assertEquals(36, NodeTypeRegistry.all().size)`) stays at 36.

- [ ] **Update docs/usage.md**

Append under the "Node categories" section, at the end:

```markdown

**Inline pin values.** Each input pin that doesn't have a wire connected shows
a small editor next to its name — type a number, toggle a checkbox, or pick from
a dropdown to set the value the node uses. Wire something in and the editor
hides; disconnect and the saved value is restored. This replaces the per-node
config sheet for most parameters; structural settings (pin counts for Switch /
Sequencer, channel name for Channel I/O, etc.) keep their dedicated UI.
```

- [ ] **Bump mod_version to 0.4.0**

```bash
sed -i 's/^mod_version=0.3.0/mod_version=0.4.0/; s/^modVersion=0.3.0/modVersion=0.4.0/' gradle.properties
```

- [ ] **Full build + test**

```bash
./gradlew build -x test
./gradlew test
```

Expected: `BUILD SUCCESSFUL` for build, only the 4 pre-existing `GroupProxyPins*` failures in tests.

- [ ] **Manual in-client verification**

Stop and ask the user — do NOT dispatch a subagent. The user should:

1. `./gradlew runClient`.
2. Place a Nodewire block, open editor, drop a `Lerp` node. Type `0.5` into the `t` pin's editor. The result should reflect that without a wired Constant.
3. Drop a `Math` node. The `op` pin should show a dropdown — click it, pick MUL, observe the math change live.
4. Open an old saved graph (from a v0.3.x save). Math / Compare / LogicGate / Timer / Delay / Probability / RandomInt / PID nodes should behave identically — the legacy config values were migrated to pin-defaults on load.
5. Save the graph, close, reopen — pin-defaults persist.
6. Wire something into a previously-edited pin. The editor disappears; the value comes from the wire. Disconnect — the saved default returns.

- [ ] **Commit**

```bash
git add -A
git commit -m "release: v0.4.0 — inline pin defaults + config-to-pin migration

LegacyConfigMigration runs at Node.CODEC.decode so old graphs (v0.3.x
and earlier) silently upgrade their config-keyed enums and scalars
into pinDefaults entries on load. New graphs use pinDefaults from
the start. Migration is idempotent and a modern pinDefaults entry
always wins over a legacy config key.

5 migration tests cover Math/Timer/RandomInt/precedence/passthrough.
docs/usage.md gets a paragraph on inline pin values.

mod_version bumped 0.3.0 -> 0.4.0."
```

---

## Self-review

**Spec coverage:**

- §`PinEditor` sealed class → Task 1.
- §`Pin` schema change → Task 2.
- §Pin-default storage → Task 3.
- §Evaluator integration → Task 4.
- §`EditorState.setPinDefault` → Task 5.
- §UI (editor composables + pin-row integration) → Tasks 6, 7, 8.
- §Config-to-pin migration (7 nodes) → Tasks 9, 10, 11.
- §Codec back-compat shim → Task 12.
- §Versioning + manual verification → Task 12.
- §Tests — `PinDefaultStorageTest` (T3), `EvaluatorPinDefaultTest` (T4), `LegacyConfigMigrationTest` (T12), + pin-default test in `AlgoNodeEvaluatorsTest` for PID clamps (T11).

**Type consistency:**

- `Pin.editor`: `PinEditor? = null` declared T2, read by `NodeType.editorFor` and the renderer T7.
- `Node.getPinDefault` / `withPinDefault`: defined T3, used by evaluator (T4), `setPinDefault` (T5), renderer (T7), migration (T12).
- `LegacyConfigMigration.migrate`: defined T12, hooked into `Node.CODEC` (T12).
- `PinDefaultEditor` composable: defined T6, upgraded T8, called from NodeCard (T7).

**Commit hygiene:** one commit per task at the end. UI composables T7+T8 split because the integration with NodeCard (T7) is needed before the Enum upgrade can be demonstrated; both still commit independently.
