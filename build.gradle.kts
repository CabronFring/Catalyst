import catalyst.build.writeJarIntegrity
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.4.3"
}

group = "gg.catalyst"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.lucko.me/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.viaversion.com/")
}

// The bot client runs in its own JVM, against the libraries Catalyst downloads at runtime
// (see LibraryManifest). It gets its own source set so MCProtocolLib's Netty 4.2 never
// reaches the main classpath, which must stay on the 4.1 API servers ship.
val bots: SourceSet by sourceSets.creating

dependencies {
    // Compile against the OLDEST version we support so the jar stays
    // binary-compatible all the way up to the latest builds.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Shaded and relocated below. Servers ship their own Jackson and change
    // its version between releases, so using theirs would break the plugin on
    // whichever server moved first.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    // Anonymous usage statistics (bstats.org), shaded and relocated below as bStats requires.
    implementation("org.bstats:bstats-bukkit:3.2.1")

    // Writes config.yml without stripping its comments, which Bukkit's own YAML does on
    // the older servers still supported. Shaded and relocated below.
    implementation("dev.dejvokep:boosted-yaml:1.3.7")

    // Netty is NOT shaded: the packet profiler has to attach to the server's own
    // pipeline, so it must bind to the server's Netty, not a private copy.
    // Compiled against 4.1 because servers from 1.16 ship that, while newer ones
    // ship 4.2 - the handler APIs used here have been stable since 4.0.
    compileOnly("io.netty:netty-transport:4.1.115.Final")

    // Provided by spark itself, which Paper bundles. Never shaded: the bridge has to
    // talk to the server's running spark instance, not a private copy.
    compileOnly("me.lucko:spark-api:0.1-20240720.200737-2")

    // Never shaded: downloaded and hash-checked at runtime. Versions must match LibraryManifest.
    "botsCompileOnly"("org.geysermc.mcprotocollib:protocol:1.21.11-20260512.221357-18")
    "botsCompileOnly"("net.raphimc:ViaLoader:3.0.4")
    "botsCompileOnly"("com.viaversion:viaversion-common:5.12.0")
    "botsCompileOnly"("com.viaversion:viabackwards-common:5.12.0")

    // Tests cover the pure-logic parts; the server API is there only so classes that
    // mention Bukkit types in their signatures can load.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// Configured by type rather than through the generated `tasks.shadowJar` accessor:
// accessors only exist after Gradle generates them, which older IDEs fail to pick up,
// leaving the whole block underlined as unresolved even though the build is fine.
tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("Catalyst")
    archiveClassifier.set("")

    relocate("com.fasterxml.jackson.annotation", "gg.catalyst.lib.jsn.annotation")
    relocate("com.fasterxml.jackson.core",       "gg.catalyst.lib.jsn.core")
    relocate("com.fasterxml.jackson.databind",   "gg.catalyst.lib.jsn.databind")
    relocate("com.fasterxml.jackson.datatype",   "gg.catalyst.lib.jsn.datatype")
    relocate("org.bstats",                        "gg.catalyst.lib.bstats")
    relocate("dev.dejvokep.boostedyaml",          "gg.catalyst.lib.yaml")
    // Signatures and module metadata are meaningless once classes are relocated,
    // and a stale signature makes the jar fail verification at load time.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/maven/**")
    exclude("module-info.class")

    minimize()

    // Compiled bot classes only; their libraries stay out and are loaded by the bot JVM.
    from(bots.output)

    // Tells Paper the jar needs no remapping. Without it Paper loads a remapped copy, which
    // would always fail the integrity check below. Safe: Catalyst reflects only on API
    // method names and matches internal fields by type, never by mapped name.
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }

    // Fingerprint the finished jar and store it inside, as TotemGuard does; the plugin
    // checks it at startup (gg.catalyst.integrity.JarIntegrityChecker).
    val outputFile = archiveFile
    doLast {
        writeJarIntegrity(outputFile.get().asFile.toPath())
    }

    // GPL-3.0 requires the licence text to travel with every copy of the binary.
    // At the jar root: META-INF/LICENSE is already taken by Jackson's Apache-2.0 text.
    from(rootProject.file("LICENSE"))
}

// Publish the shaded jar, not the thin one. Task names as strings for the same reason.
tasks.named("build") { dependsOn("shadowJar") }
tasks.named<Jar>("jar") { enabled = false }
