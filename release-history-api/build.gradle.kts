plugins {
    application
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":api-model"))
    implementation(project(":release-operator"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.fabric8.kubernetes.client)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

application {
    mainClass.set("dev.weihantu.releaseguard.history.ReleaseHistoryApplication")
}
