plugins {
    application
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":api-model"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass.set("dev.weihantu.releaseguard.gate.MetricGateApplication")
}
