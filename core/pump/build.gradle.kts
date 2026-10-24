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
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
