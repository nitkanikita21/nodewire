# Nodewire script authoring template

Write Nodewire scripts (`*.nw.kts`) in IntelliJ with full autocomplete and
type-checking, then paste them into the in-world **📜 Edit** editor.

## One-time setup

1. From the **mod** repo root, build + export the script facade jar:
   ```bash
   ./gradlew :scripting:exportScriptApi
   ```
   This drops `script-template/libs/script-api.jar` (the `dev.nitka.nodewire.script.*`
   facade). Re-run it whenever the DSL changes.

2. Open the `script-template/` folder as a **separate** project in IntelliJ
   (File → Open → pick `script-template`). Let Gradle import finish.

3. If `*.nw.kts` files don't light up immediately:
   - File → Invalidate Caches… → Invalidate and Restart.
   - Check Settings → Languages & Frameworks → Kotlin → Kotlin Scripting — the
     `NwScriptIde` definition (from `:defs`) should be listed and enabled.
   IntelliJ's custom-script support sometimes needs one reload to register a new
   definition.

## Writing scripts

Put files under `scripts/src/main/kotlin/` with the `.nw.kts` extension. Inside,
`this` is a `ScriptModule`, so the DSL resolves unqualified — no imports:

```kotlin
val enable = input<Boolean>("enable")
val out = output<Redstone>("out")
var t by state(0)

tick {
    if (!enable.value) { out.value = Redstone.OFF; return@tick }
    t = (t + 1) % 20
    out.value = if (t < 10) Redstone.MAX else Redstone.OFF
}
```

See `scripts/src/main/kotlin/blink.nw.kts` for a runnable example.

Copy the file (or just the body) into the in-world editor — the in-game script
definition uses the same default imports + implicit receiver, so it pastes as-is.

## How it works

- `defs/` declares `@KotlinScript(fileExtension = "nw.kts")` → `NwScriptIde` with
  `defaultImports("dev.nitka.nodewire.script.*")` + `implicitReceivers(ScriptModule)`.
  Its `META-INF/kotlin/script/templates/…` resource is how IntelliJ discovers it.
- `scripts/` depends on `:defs` + `script-api.jar`, so any `.nw.kts` here is
  resolved against the Nodewire facade.

This mirrors the mod's runtime definition (`…script.host.NwScript`); only the
classpath wiring differs (here: `dependenciesFromCurrentContext`; in-game:
explicit extracted jars, because NeoForge serves the mod over `union://`).
