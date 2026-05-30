plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The Nodewire script facade (ScriptModule, input/output/state/tick, Redstone,
    // Vec…). Built by the mod: ./gradlew :scripting:exportScriptApi drops it here.
    compileOnly(files("../libs/script-api.jar"))

    // Kotlin scripting API — needed to declare the @KotlinScript definition.
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:2.0.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.0.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.0.20")
}
