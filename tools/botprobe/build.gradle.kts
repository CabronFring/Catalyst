plugins { java; application }
repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.viaversion.com/")
}
dependencies {
    implementation("org.geysermc.mcprotocollib:protocol:1.21.11-20260512.221357-18")
    implementation("net.raphimc:ViaLoader:3.0.4")
    implementation("com.viaversion:viaversion-common:5.12.0")
    implementation("com.viaversion:viabackwards-common:5.12.0")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
application { mainClass.set("BotProbe") }
tasks.register("printClasspath") { doLast { configurations.runtimeClasspath.get().forEach { println(it) } } }
tasks.register("printCoordinates") {
    doLast {
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach {
            val id = it.moduleVersion.id
            println("COORD ${id.group}:${id.name}:${id.version}:${it.classifier ?: ""}")
        }
    }
}
