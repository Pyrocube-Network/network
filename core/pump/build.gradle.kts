plugins {
    id("network.java-common")
}

dependencies {
    api(libs.minestom)
    api(libs.zstdJni)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
    testRuntimeOnly(libs.logback)
}

val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.test {
    useJUnitPlatform {
        excludeTags("perf")
    }
    jvmArgs(nativeAccess)
    maxHeapSize = "2g"
}

tasks.register<Test>("perfTest") {
    group = "verification"
    description = "Runs the PUMP performance suite (tagged 'perf'); results are printed to stdout."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    useJUnitPlatform {
        includeTags("perf")
    }
    jvmArgs(nativeAccess)
    maxHeapSize = "4g"

    // Performance numbers must never be served from the build cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

tasks.register<JavaExec>("convertWorld") {
    group = "network"
    description =
        "Converts a region directory between Anvil and PUMP: " +
            "-Pmode=anvil-to-pump|pump-to-anvil -Psource=<dir> -Ptarget=<dir> [-PextraArgs=\"--level=6\"]"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.pyrocube.core.pump.anvil.PumpConvertTool")
    jvmArgs(nativeAccess)

    val mode = providers.gradleProperty("mode")
    val source = providers.gradleProperty("source")
    val target = providers.gradleProperty("target")
    val extra = providers.gradleProperty("extraArgs").orElse("")
    argumentProviders.add(
        CommandLineArgumentProvider {
            require(mode.isPresent && source.isPresent && target.isPresent) {
                "Pass -Pmode=anvil-to-pump|pump-to-anvil -Psource=<dir> -Ptarget=<dir>"
            }
            listOf(mode.get(), source.get(), target.get()) +
                extra.get().split(" ").filter { it.isNotBlank() }
        },
    )
}
