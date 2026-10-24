plugins {
    id("network.minestom-server")
}

application {
    mainClass.set("net.pyrocube.lobby.LobbyServer")
}

tasks.named<JavaExec>("runServer") {
    environment("SERVER_PORT", "25565")
    environment("VELOCITY_SECRET", "mi_token_secreto_local")
}