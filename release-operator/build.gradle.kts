plugins {
    application
}

dependencies {
    implementation(project(":api-model"))
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.java.operator.sdk)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.fabric8.kube.api.test.client.inject)
}

application {
    mainClass.set("dev.weihantu.releaseguard.operator.ReleaseGuardOperator")
}
