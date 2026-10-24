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

    dependsOn(provider { subprojects.flatMap { p -> p.tasks.matching { it.name == "runServer" } } })
}


tasks.register<Delete>("cleanNetworkRuntime") {
    description = "Delete the shared run directory."
    group = "network"
    delete(layout.projectDirectory.dir("run"))
}