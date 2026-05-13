# Nodewire UI — Component examples

API surface that the framework will deliver once Phases 4–12 are done. Code below compiles in your head, not in IDE yet — Phases 4–11 add the actual composables. This doc is the **target** the implementation aims at.

---

## 1. Hello world

The smallest possible screen:

```kotlin
class HelloScreen : NwComposeScreen(Component.literal("Hello")) {
    @Composable override fun Content() {
        NwThemeProvider {
            Text("hello, nodewire")
        }
    }
}

// Open from anywhere on client:
Minecraft.getInstance().setScreen(HelloScreen())
```

That's it. `NwComposeScreen` extends MC `Screen` and bridges the Compose runtime to MC's render/input loop. `NwThemeProvider` wraps in default dark colors / dimens / typography.

---

## 2. State + recomposition

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Column(Modifier.padding(16)) {
        Text("Count: $count")
        Row(horizontalArrangement = Arrangement.spacedBy(8)) {
            Button(onClick = { count-- }) { Text("−") }
            Button(onClick = { count++ }) { Text("+") }
            Button(onClick = { count = 0 }, style = ButtonDefaults.ghost()) { Text("reset") }
        }
    }
}
```

`mutableStateOf` is **real Jetpack Compose state**. Writing to `count` invalidates only what reads it (the `Text`) — recomposition is granular.

---

## 3. Layout

Free-form panels with the full flexbox vocabulary:

```kotlin
@Composable
fun Toolbar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24)
            .background(NwTheme.colors.surface)
            .border(BorderStroke(1, NwTheme.colors.border))
            .padding(horizontal = 8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Center,
    ) {
        // Left: icon + title
        Row(verticalAlignment = Alignment.Center, horizontalArrangement = Arrangement.spacedBy(6)) {
            Icon(loc("nodewire:icon/logo"))
            Text("Editor", style = NwTheme.typography.subtitle)
        }
        // Center: flex spacer
        Spacer(Modifier.weight(1f))
        // Right: actions
        Row(horizontalArrangement = Arrangement.spacedBy(4)) {
            Button(onClick = ::save,  style = ButtonDefaults.ghost()) { Icon(loc("nodewire:icon/save")) }
            Button(onClick = ::close, style = ButtonDefaults.ghost()) { Icon(loc("nodewire:icon/close")) }
        }
    }
}
```

What `Modifier` does (each piece is independent):
- `.fillMaxWidth()` — Yoga `setWidthPercent(100)`
- `.height(24)` — Yoga `setHeight(24)`
- `.background(color)` — render-time fill (not layout)
- `.border(stroke)` — render-time stroke
- `.padding(horizontal = 8)` — Yoga `setPadding(LEFT/RIGHT, 8)`

Order matters: `.padding(8).background(red)` paints red around the padding; `.background(red).padding(8)` paints red only inside content.

---

## 4. Theming + styling

### Global theme

```kotlin
@Composable
override fun Content() {
    NwThemeProvider(
        // override individual tokens; rest inherits from defaults
        colors = NwColors.Dark.copy(accent = Color(0xFF_FF_8B_3D.toInt()))
    ) {
        EditorRoot()
    }
}
```

Inside `EditorRoot()`, `NwTheme.colors.accent` resolves to orange.

### Per-component style (Layer 2)

```kotlin
// Built-in variants
Button(onClick = {})                                          { Text("Save") }   // filled
Button(onClick = {}, style = ButtonDefaults.outlined())       { Text("Cancel") } // outlined
Button(onClick = {}, style = ButtonDefaults.ghost())          { Text("More") }   // ghost
Button(onClick = {}, style = ButtonDefaults.danger())         { Text("Delete") } // danger

// Per-instance tweak — start from a default, override one field
Button(
    onClick = {},
    style = ButtonDefaults.filled(container = NwTheme.colors.success)
) { Text("OK") }

// Define your own reusable variant once
@Composable
fun ButtonDefaults.accent2() = filled(container = Color(0xFF_AA_44_CC.toInt()))

Button(onClick = {}, style = ButtonDefaults.accent2()) { Text("Special") }
```

### Per-call-site override (Layer 3)

Modifier always wins over component style — for one-off escapes:

```kotlin
Button(
    onClick = {},
    modifier = Modifier.background(Color.Red).border(BorderStroke(3, Color.Yellow))
) { Text("hot") }
```

---

## 5. Authoring your own component

Components are just `@Composable` functions. There's no "Component class". The framework only cares about three things:

1. **What goes in the modifier** (size, padding) — drives layout
2. **What you draw** (via the `renderer` arg to `Layout`, or by composing existing components)
3. **How children compose** (`content: @Composable () -> Unit`)

### High-level component (composes other components)

```kotlin
@Composable
fun Card(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        style = SurfaceDefaults.outlined(),
    ) {
        Column(Modifier.padding(12)) {
            Text(title, style = NwTheme.typography.subtitle)
            Divider(Modifier.padding(vertical = 6))
            content()
        }
    }
}

// Usage
Card(title = "Inputs", modifier = Modifier.width(200)) {
    Text("a: 0.5")
    Text("b: true")
    Text("c: Vec3(1,2,3)")
}
```

### Low-level component (custom drawing)

Drop down to `Layout`, attach a custom `Renderer`, and you're painting raw shapes:

```kotlin
/** A pin dot: 8×8 circle in the pin's type color, with optional label to the right. */
@Composable
fun PinDot(
    color: Color,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.Center, modifier = modifier) {
        Layout(
            modifier = Modifier.size(8),
            renderer = object : Renderer {
                override fun NwCanvas.render(node: UiNode) {
                    val cx = node.layoutWidth / 2
                    val cy = node.layoutHeight / 2
                    fillCircle(cx, cy, radius = 4, color = color)
                }
            },
        )
        if (label != null) {
            Spacer(Modifier.width(4))
            Text(label, style = NwTheme.typography.caption)
        }
    }
}
```

`Layout(modifier, renderer, content)` is the primitive: it emits one `UiNode` and gives you a hook to paint on the canvas. `NwCanvas` exposes `fillRect`, `fillCircle`, `fillShape`, `drawText`, `drawTexture`, `drawLine`, plus `pushOffset`/`pushClip` for nested transforms.

### Custom layout (you control measure/place)

99% of the time you compose `Row`/`Column`/`Box`. For the 1% you need *unusual* placement (e.g. radial layout, force-directed graph) you can set Yoga properties directly via `Layout`'s `yogaConfig`:

```kotlin
@Composable
fun StackedExactlyAt(positions: List<IntOffset>, content: @Composable (Int) -> Unit) {
    Box {  // relative parent — children with positionType=ABSOLUTE are placed within
        positions.forEachIndexed { i, pos ->
            Box(Modifier.absolutePosition(pos.x, pos.y)) {
                content(i)
            }
        }
    }
}
```

`Modifier.absolutePosition(x, y)` sets `positionType=ABSOLUTE` + `position(LEFT/TOP, …)` on the Yoga node — exactly what the node editor canvas uses for placing draggable nodes.

---

## 6. Putting it together: a tiny node-editor mock

```kotlin
class NodeEditorScreen(val pos: BlockPos) : NwComposeScreen(Component.literal("Logic Block")) {
    @Composable override fun Content() {
        NwThemeProvider {
            Row(Modifier.fillMaxSize()) {
                Palette(Modifier.width(140))
                Divider(Modifier.fillMaxHeight().width(1))
                Canvas(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Palette(modifier: Modifier) {
    Column(modifier.background(NwTheme.colors.surface).padding(8),
           verticalArrangement = Arrangement.spacedBy(4)) {
        Text("Logic", style = NwTheme.typography.caption)
        PaletteItem("AND") { addNode("nodewire:and") }
        PaletteItem("OR")  { addNode("nodewire:or") }
        PaletteItem("NOT") { addNode("nodewire:not") }
        Spacer(Modifier.height(8))
        Text("Constants", style = NwTheme.typography.caption)
        PaletteItem("bool")  { addNode("nodewire:bool_const") }
        PaletteItem("float") { addNode("nodewire:float_const") }
        PaletteItem("vec3")  { addNode("nodewire:vec3_const") }
    }
}

@Composable
private fun PaletteItem(name: String, onAdd: () -> Unit) {
    Button(onClick = onAdd, style = ButtonDefaults.ghost(),
           modifier = Modifier.fillMaxWidth()) {
        Text(name)
    }
}

@Composable
private fun Canvas(modifier: Modifier) {
    val nodes = remember { mutableStateListOf<EditorNode>() }
    Box(modifier.background(Color(0xFF_0E_0E_12.toInt()))) {
        nodes.forEach { node ->
            DraggableNode(node)
        }
    }
}

@Composable
private fun DraggableNode(node: EditorNode) {
    var x by remember { mutableStateOf(node.x) }
    var y by remember { mutableStateOf(node.y) }
    Surface(
        modifier = Modifier
            .absolutePosition(x, y)
            .width(120)
            .pointerInput { ev ->
                if (ev is PointerEvent.Drag) { x += ev.dx; y += ev.dy; true } else false
            },
        style = SurfaceDefaults.outlined(),
    ) {
        Column(Modifier.padding(6)) {
            Text(node.title, style = NwTheme.typography.subtitle)
            Divider(Modifier.padding(vertical = 4))
            node.inputs.forEach { pin ->
                PinDot(pinColor(pin.type), pin.name)
            }
        }
    }
}

private fun pinColor(t: PinType): Color = when (t) {
    PinType.BOOL  -> NwTheme.colors.pinBool   // but read at composition time — see note
    PinType.INT   -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.VEC2  -> NwTheme.colors.pinVec2
    PinType.VEC3  -> NwTheme.colors.pinVec3
    PinType.QUAT  -> NwTheme.colors.pinQuat
}
```

Notes for the diligent reader:
- `NwTheme.colors` only works inside `@Composable`. The `pinColor` helper above must itself be `@Composable` (or take colors as a param). In the real code: `@Composable fun pinColor(t: PinType): Color = …`.
- `mutableStateListOf` triggers recomposition on `add`/`remove`/`set` — perfect for the node list.
- `.pointerInput { … }` returns `true` when the event is consumed; otherwise it bubbles to ancestors. Returning `true` on `Drag` makes the canvas behind not steal the drag.

---

## 7. Patterns you'll use repeatedly

### Conditional UI

```kotlin
if (selectedNode != null) {
    Inspector(node = selectedNode)
}
```

Standard Kotlin — Compose figures out the slot-table diff for you.

### List of items

```kotlin
Column {
    items.forEach { item ->
        ListItem(item, onClick = { open(item) })
    }
}
```

For very long lists, defer to a `LazyColumn` (not in MVP — add later).

### Animations

`compose.runtime` ships `animate*AsState` indirectly via `animation-core` (need to add that dep). Out of MVP. Easy upgrade later.

### Sharing state across screens

A `mutableStateOf` field in a singleton object (or a `ViewModel`-like class) survives screen re-creation. Recomposition still works because state reads cross composition boundaries.

```kotlin
object EditorState {
    val nodes = mutableStateListOf<EditorNode>()
    var selected by mutableStateOf<EditorNode?>(null)
}

@Composable
fun Inspector() {
    val s = EditorState.selected ?: return
    Text("Selected: ${s.title}")
}
```

---

## What's left to build (Phases 4–12)

This doc shows the **eventual** API. After Phase 3 (just committed), the framework can:
- mirror Compose mutations into a Yoga tree (no rendering)
- read laid-out positions from Yoga

It can NOT yet:
- render anything to the screen (Phase 4)
- run inside an MC `Screen` (Phase 5)
- offer `Layout` / `Box` / `Row` / `Column` composables (Phase 6–7)
- read `NwTheme.colors` (Phase 8)
- render `Text` (Phase 9)
- handle clicks (Phase 10)
- offer `Button` / `Surface` (Phase 11)
- show tooltips (Phase 12)

When Phase 12 finishes, all examples in this doc compile and run.
