fun configureEnvironment(task: ProcessForkOptions) {
    val f = File("${rootProject.projectDir}/.env.properties")
    if (f.isFile) {
        val props = java.util.Properties()
        f.inputStream().use { props.load(it) }
        props.forEach { key, value ->
            task.environment[key.toString()] = value.toString()
        }
    }
}
extra["configureEnvironment"] = { task: ProcessForkOptions -> configureEnvironment(task) }