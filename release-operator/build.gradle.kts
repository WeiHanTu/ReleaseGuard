dependencies {
    implementation(project(":api-model"))
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.java.operator.sdk)
    testImplementation(libs.fabric8.kube.api.test.client.inject)
}
