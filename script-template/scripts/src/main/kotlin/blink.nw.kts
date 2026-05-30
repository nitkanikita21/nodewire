// Example Nodewire script. With the template imported into IntelliJ you get
// completion + type-checking on everything below. Copy the whole file (or just
// the body) into the in-world "📜 Edit" editor — it pastes as-is.

val enable = input<Boolean>("enable")
val out = output<Redstone>("out")

var t by state(0)
var was by state(false)

tick {
    if (enable.value && !was) chat("script enabled!")
    was = enable.value

    if (!enable.value) {
        out.value = Redstone.OFF
        return@tick
    }

    t = (t + 1) % 20
    out.value = if (t < 10) Redstone.MAX else Redstone.OFF
}
