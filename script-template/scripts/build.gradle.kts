plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The facade — so ScriptModule & friends resolve inside *.nw.kts.
    implementation(files("../libs/script-api.jar"))

    // The .nw.kts definition (carries the META-INF discovery file). IntelliJ scans
    // this module's dependency jars, finds the template, and applies it to *.nw.kts.
    implementation(project(":defs"))

    // Scripting host on the classpath activates IntelliJ's script support.
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.0.20")
}
