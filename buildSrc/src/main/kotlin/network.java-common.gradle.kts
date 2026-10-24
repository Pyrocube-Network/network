plugins {
    id("java-library")
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        target("src/main/java/**/*.java")

        expandWildcardImports()
        forbidModuleImports()

        googleJavaFormat("1.36.1").aosp()

        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}

tasks.named("compileJava") {
    dependsOn("spotlessCheck")
}