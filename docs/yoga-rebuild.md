# Rebuilding the bundled Yoga jar

`libs/yoga-1.0.0-j17.jar` is the AppliedEnergistics fork of Yoga (Facebook's flexbox layout engine), rebuilt locally for Java 17.

## Why we vendor it

The upstream `org.appliedenergistics.yoga:yoga:1.0.0` on Maven Central is compiled to Java 21 bytecode (`JavaLanguageVersion.of(21)` in its `settings.gradle`). Forge 1.20.1 / Minecraft 1.20.1 runs on Java 17 — Gradle refuses to put a Java-21 jar on a Java-17 `runtimeClasspath`.

The source itself is Java-17-compatible (only records, which are stable since Java 16). We rebuild the same source with a Java-17 toolchain and vendor the result.

## Rebuild steps

```bash
mkdir -p /tmp/yoga && cd /tmp/yoga
curl -sL https://github.com/AppliedEnergistics/yoga/archive/refs/tags/v1.0.0.tar.gz -o yoga.tgz
tar xzf yoga.tgz
cd yoga-1.0.0
sed -i 's/JavaLanguageVersion.of(21)/JavaLanguageVersion.of(17)/' settings.gradle
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :jar
```

Verify it's Java 17 bytecode (major version 61):

```bash
javap -v -cp build/libs/yoga-*.jar org.appliedenergistics.yoga.YogaNode | grep "major version"
```

Copy into the mod:

```bash
cp build/libs/yoga-*.jar /path/to/nodewire/libs/yoga-1.0.0-j17.jar
```

The mod's `build.gradle.kts` references it via `implementation(files("libs/yoga-1.0.0-j17.jar"))` and shades it into the final mod jar.

## When to bump

If we update to a new Yoga version or apply patches, repeat the above with the new tag. The `-j17` filename suffix makes it explicit that this jar isn't the upstream Maven artifact — nobody should assume they can just bump the version coordinate.
