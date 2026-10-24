import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("network.java-common")
    id("application")
    alias(libs.plugins.shadow)
}

val projectKey = "version.${project.name}"
if (project.hasProperty(projectKey)) {
    project.version = project.property(projectKey).toString()
} else {
    project.version = "dev"
}

dependencies {
    compileOnly(project(":core:api"))
    runtimeOnly(project(":core:internal"))
    implementation(project(":core:pump"))
    implementation(libs.minestom)
}


tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")

    minimize {
        exclude(project(":core:api"))
        exclude(project(":core:internal"))
        exclude(project(":core:pump"))
        exclude(dependency("com.github.luben:zstd-jni:.*"))
    }

    destinationDirectory.set(rootProject.layout.buildDirectory.dir("network-jars"))
}

tasks.register<JavaExec>("runServer") {
    group = "application"

    val appExtension = extensions.getByType<JavaApplication>()
    mainClass.set(appExtension.mainClass)

    val javaExtension = extensions.getByType<JavaPluginExtension>()
    val mainSourceSet = javaExtension.sourceSets.named("main")
    classpath = mainSourceSet.map { it.runtimeClasspath }.get()

    standardInput = System.`in`

    systemProperty("minestom.development", "true")
    notCompatibleWithConfigurationCache("La tarea runServer interactiva con entrada estándar de consola no es compatible con el Configuration Cache.")
}
