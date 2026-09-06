plugins {
    application
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":api-model"))
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.java.operator.sdk)
    implementation("org.apache.kafka:kafka-clients")
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.fabric8.kube.api.test.client.inject)
}

application {
    mainClass.set("dev.weihantu.releaseguard.operator.ReleaseGuardOperator")
}
