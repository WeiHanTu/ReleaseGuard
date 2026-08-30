import java.nio.file.Files

val crdGeneratorCli = configurations.create("crdGeneratorCli")

dependencies {
    api(libs.fabric8.kubernetes.client.api)
    api(libs.fabric8.generator.annotations)

    crdGeneratorCli(libs.fabric8.crd.generator.cli)

    testImplementation(libs.jqwik)
}

val generatedCrdDirectory = layout.buildDirectory.dir("generated/crd")
val committedCrdDirectory = rootProject.layout.projectDirectory.dir("config/crd")
val chartCrdDirectory = rootProject.layout.projectDirectory.dir("charts/releaseguard/crds")
val mainSourceSet = sourceSets.main.get()

val generateCrds =
    tasks.register<JavaExec>("generateCrds") {
        description = "Generates structural Kubernetes CRDs from the Java API models."
        group = "crd"
        dependsOn(tasks.named("classes"))

        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            },
        )
        classpath = crdGeneratorCli
        mainClass.set("io.fabric8.crd.generator.cli.CRDGeneratorCLI")
        inputs.files(mainSourceSet.output.classesDirs).withPropertyName("modelClasses")
        inputs
            .files(mainSourceSet.compileClasspath)
            .withPropertyName("modelClasspath")
            .withNormalizer(ClasspathNormalizer::class)
        outputs.dir(generatedCrdDirectory)

        doFirst {
            val outputDirectory = generatedCrdDirectory.get().asFile
            delete(outputDirectory)
            outputDirectory.mkdirs()

            val modelClasspath = mainSourceSet.compileClasspath.files + mainSourceSet.output.classesDirs.files
            args =
                buildList {
                    add("--output-dir")
                    add(outputDirectory.absolutePath)
                    modelClasspath.sortedBy { it.absolutePath }.forEach {
                        add("--classpath")
                        add(it.absolutePath)
                    }
                    mainSourceSet.output.classesDirs.files.sortedBy { it.absolutePath }.forEach {
                        add(it.absolutePath)
                    }
                }
        }
    }

val syncCommittedCrds =
    tasks.register<Sync>("syncCommittedCrds") {
        group = "crd"
        dependsOn(generateCrds)
        from(generatedCrdDirectory)
        into(committedCrdDirectory)
    }

val syncChartCrds =
    tasks.register<Sync>("syncChartCrds") {
        group = "crd"
        dependsOn(generateCrds)
        from(generatedCrdDirectory)
        into(chartCrdDirectory)
    }

tasks.register("syncCrds") {
    description = "Replaces committed and Helm-packaged CRDs with deterministic generated output."
    group = "crd"
    dependsOn(syncCommittedCrds, syncChartCrds)
}

val checkCrdFreshness =
    tasks.register("checkCrdFreshness") {
        description = "Fails when committed CRDs differ from generated output."
        group = "verification"
        dependsOn(generateCrds)
        mustRunAfter(syncCommittedCrds, syncChartCrds)
        inputs.dir(generatedCrdDirectory)
        inputs.dir(committedCrdDirectory)
        inputs.dir(chartCrdDirectory)

        doLast {
            fun filesByRelativePath(directory: File): Map<String, ByteArray> =
                if (!directory.exists()) {
                    emptyMap()
                } else {
                    Files.walk(directory.toPath()).use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .sorted()
                            .toList()
                            .associate { path ->
                                directory.toPath().relativize(path).toString() to Files.readAllBytes(path)
                            }
                    }
                }

            val generated = filesByRelativePath(generatedCrdDirectory.get().asFile)
            val committed = filesByRelativePath(committedCrdDirectory.asFile)
            val chart = filesByRelativePath(chartCrdDirectory.asFile)
            val differentCommitted =
                (generated.keys + committed.keys).sorted().filter { key ->
                    val generatedBytes = generated[key]
                    val committedBytes = committed[key]
                    generatedBytes == null || committedBytes == null || !generatedBytes.contentEquals(committedBytes)
                }
            val differentChart =
                (generated.keys + chart.keys).sorted().filter { key ->
                    val generatedBytes = generated[key]
                    val chartBytes = chart[key]
                    generatedBytes == null || chartBytes == null || !generatedBytes.contentEquals(chartBytes)
                }
            if (differentCommitted.isNotEmpty() || differentChart.isNotEmpty()) {
                throw GradleException(
                    "Committed CRDs are stale: config=[${differentCommitted.joinToString()}], " +
                        "chart=[${differentChart.joinToString()}]. " +
                        "Run ./gradlew :api-model:syncCrds and commit the result.",
                )
            }
        }
    }

tasks.test {
    dependsOn(generateCrds)
    systemProperty("releaseguard.generatedCrdDirectory", generatedCrdDirectory.get().asFile)
    systemProperty("releaseguard.repositoryRoot", rootProject.projectDir)
}

tasks.check {
    dependsOn(checkCrdFreshness)
}
