fun version(name: String): String = providers.gradleProperty(name).get()

val logstashLogbackEncoderVersion = version("logstash-logback-encoder.version")
val lokiLogbackAppenderVersion = version("loki-logback-appender.version")
val springdocOpenapiStarterWebfluxUiVersion = version("springdoc-openapi-starter-webflux-ui.version")
val shardingsphereVersion = version("shardingsphere.version")

plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.openapi.generator")
}

extra["testcontainers.version"] = version("testcontainers.version")

group = project.findProperty("group") as String? ?: "ru.transaction.service"
version = project.findProperty("version") as String? ?: "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    modularity.inferModulePath = false
}

repositories {
    mavenCentral {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.data:spring-data-envers")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.apache.shardingsphere:shardingsphere-jdbc:$shardingsphereVersion")
    implementation("org.apache.shardingsphere:shardingsphere-transaction-xa-core:$shardingsphereVersion")
    implementation("javax.transaction:javax.transaction-api:1.3")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocOpenapiStarterWebfluxUiVersion")

    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")
    implementation("com.github.loki4j:loki-logback-appender:$lokiLogbackAppenderVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val openApiOutputDir = layout.buildDirectory.dir("generated")

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$projectDir/openapi/transaction-service.yaml")
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    ignoreFileOverride.set("$projectDir/openapi/.openapi-generator-ignore")
    apiPackage.set("ru.transaction.service.generated.api")
    modelPackage.set("ru.transaction.service.generated.dto")
    invokerPackage.set("ru.transaction.service.generated")
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
    typeMappings.set(mapOf(
        "number" to "BigDecimal"
    ))
    importMappings.set(mapOf(
        "BigDecimal" to "java.math.BigDecimal"
    ))
    configOptions.set(
        mapOf(
            "library" to "spring-boot",
            "configPackage" to "ru.transaction.service.generated.configuration",
            "delegatePattern" to "true",
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true",
            "dateLibrary" to "java8",
            "serializationLibrary" to "jackson",
            "openApiNullable" to "false",
            "hideGenerationTimestamp" to "true",
            "documentationProvider" to "none"
        )
    )
}

sourceSets {
    named("main") {
        java.srcDir(openApiOutputDir.map { it.dir("src/main/java") })
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
}
