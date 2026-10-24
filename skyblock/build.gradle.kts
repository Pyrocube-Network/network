plugins {
    id("network.minestom-server")
}

application {
    mainClass.set("net.pyrocube.skyblock.SkyblockServer")
}

tasks.named<JavaExec>("runServer") {
    environment("SERVER_PORT", "25566")
    environment("VELOCITY_SECRET", "mi_token_secreto_local")
}