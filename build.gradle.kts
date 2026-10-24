tasks.register("buildNetwork") {
    group = "network"
    description = "Compile the necessary jars for the network."

    val compilableProjects = subprojects.filter { it.subprojects.isEmpty() }
    dependsOn(compilableProjects.map { it.tasks.named("build") })

    doLast {
        println("=========================================================")
        println("All network jars compiled successfully. The files are in")
        println("'build/libs/' of each module")
        println("=========================================================")
    }
}

tasks.register("runNetworkDev") {
    group = "network"
    description = "Deploy a dev version of the entire network."

    val compilableProjects = subprojects.filter { it.subprojects.isEmpty() }
    dependsOn(compilableProjects.map { it.tasks.named("runServer") })
}


tasks.register("cleanNetworkRuntime") {
    description = ""
    group = "network"
    doLast {
        val runDir = layout.projectDirectory.dir("run").asFile
        if (runDir.exists()) runDir.deleteRecursively()
    }
}