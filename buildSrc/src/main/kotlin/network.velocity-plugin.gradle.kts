import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named

plugins {
    id("network.java-common")
    alias(libs.plugins.shadow)
}

val projectKey = "version.${project.name}"
if (project.hasProperty(projectKey)) {
    project.version = project.property(projectKey).toString()
} else {
    project.version = "dev"
}

repositories {
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly(libs.velocityApi)
    compileOnly(project(":core:api"))
    runtimeOnly(project(":core:internal"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")

    minimize {
        exclude(dependency("com.velocitypowered:velocity-api:.*"))
    }

    destinationDirectory.set(rootProject.layout.buildDirectory.dir("network-jars"))
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
