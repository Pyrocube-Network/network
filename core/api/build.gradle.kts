plugins {
    id("network.java-common")
}

dependencies {
    api(libs.minestom)
    api(libs.slf4j)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitLauncher)
}

tasks.test {
    useJUnitPlatform()
}
