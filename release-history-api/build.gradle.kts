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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.fabric8.kubernetes.client)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}

application {
    mainClass.set("dev.weihantu.releaseguard.history.ReleaseHistoryApplication")
}
