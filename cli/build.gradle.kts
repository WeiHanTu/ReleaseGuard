plugins {
    application
}

dependencies {
    implementation(project(":api-model"))
    implementation(project(":release-operator"))
    implementation(libs.fabric8.kubernetes.client.api)
}

application {
    applicationName = "releaseguard"
    mainClass.set("dev.weihantu.releaseguard.cli.ReleaseGuardCli")
}

tasks.test {
    systemProperty("releaseguard.repositoryRoot", rootProject.projectDir)
}
