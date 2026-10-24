plugins {
    id("network.minestom-server")
}

application {
    mainClass.set("net.pyrocube.mmorpg.MMOServer")
}

tasks.named<JavaExec>("runServer") {
    environment("SERVER_PORT", "25567")
    environment("VELOCITY_SECRET", "mi_token_secreto_local")
}