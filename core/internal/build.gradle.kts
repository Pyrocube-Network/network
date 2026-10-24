plugins {
    id("network.java-common")
}

dependencies {
    api(project(":core:api"))

    testImplementation(libs.logback)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
}

tasks.test {
    useJUnitPlatform()
}
