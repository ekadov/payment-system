fun version(name: String): String = providers.gradleProperty(name).get()

val springdocOpenapiStarterWebfluxUiVersion = version("springdoc-openapi-starter-webflux-ui.version")
val snakeyamlVersion = version("snakeyaml.version")
val logstashLogbackEncoderVersion = version("logstash-logback-encoder.version")
val lokiLogbackAppenderVersion = version("loki-logback-appender.version")
val fakerVersion = version("faker.version")
val mapstructVersion = version("mapstruct.version")
val mapstructProcessorVersion = version("mapstruct-processor.version")
val mockwebserverVersion = version("mockwebserver.version")
val testcontainersJunitJupiterVersion = version("testcontainers-junit-jupiter.version")

plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.openapi.generator")
}

group = project.findProperty("group") as String? ?: "ru.individuals.api"
version = project.findProperty("version") as String? ?: "0.0.1-SNAPSHOT"

repositories {
    maven {
        url = uri("$rootDir/../infrastructure/local-repo")
    }

    mavenCentral {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:$springdocOpenapiStarterWebfluxUiVersion")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    implementation("io.github.openfeign:feign-core:13.6")
    implementation("io.github.openfeign:feign-jackson:13.6")
    implementation("io.github.openfeign:feign-okhttp:13.6")
    implementation("io.github.openfeign:feign-slf4j:13.6")
    implementation("io.github.openfeign.form:feign-form:3.8.0")
    implementation("io.github.openfeign:feign-micrometer:13.6")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersJunitJupiterVersion")
    testImplementation("com.squareup.okhttp3:mockwebserver:$mockwebserverVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")
    implementation("com.github.loki4j:loki-logback-appender:$lokiLogbackAppenderVersion")
    implementation("com.github.javafaker:javafaker:$fakerVersion")

    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructProcessorVersion")
}

val openApiOutputDir = layout.buildDirectory.dir("generated-sources/openapi")
val keycloakOpenApiOutputDir = layout.buildDirectory.dir("generated-sources/keycloak-openapi")
val transactionOpenApiOutputDir = layout.buildDirectory.dir("generated-sources/transaction-openapi")

openApiGenerate {
    generatorName.set("java")
    inputSpec.set("$projectDir/openapi/individuals-api.yaml")
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    apiPackage.set("ru.individuals.api.generated")
    modelPackage.set("ru.individuals.api.dto")
    invokerPackage.set("ru.individuals.api.generated.invoker")
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
    cleanupOutput.set(true)
    schemaMappings.set(
        listOf(
            "CreateWalletRequest", "WalletResponse", "WalletStatus", "TransactionType", "TransactionStatus",
            "TransactionRequest", "TransactionInitResponse", "TransactionConfirmResponse",
            "TransactionStatusResponse", "TransactionPageResponse"
        ).associateWith { "ru.individuals.api.client.transaction.generated.model.$it" }
    )
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "library" to "webclient",
            "serializationLibrary" to "jackson",
            "useBeanValidation" to "true",
            "useJakartaEe" to "true",
            "interfaceOnly" to "false"
        )
    )
    globalProperties.set(
        mapOf(
            "models" to "",
            "apis" to "false",
            "supportingFiles" to "false"
        )
    )
}

sourceSets {
    named("main") {
        java.srcDir(openApiOutputDir.map { it.dir("src/main/java") })
        java.srcDir(keycloakOpenApiOutputDir.map { it.dir("src/main/java") })
        java.srcDir(transactionOpenApiOutputDir.map { it.dir("src/main/java") })
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(tasks.named("generateKeycloakClient"))
    dependsOn(tasks.named("generateTransactionClient"))
}

tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateTransactionClient") {
    description = "Transaction Service Feign client generation"
    generatorName.set("java")
    library.set("feign")
    inputSpec.set("$rootDir/../transaction-service/openapi/transaction-service.yaml")
    outputDir.set(transactionOpenApiOutputDir.get().asFile.absolutePath)
    apiPackage.set("ru.individuals.api.client.transaction.generated.api")
    modelPackage.set("ru.individuals.api.client.transaction.generated.model")
    invokerPackage.set("ru.individuals.api.client.transaction.generated.invoker")
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
    configOptions.set(mapOf(
        "library" to "feign",
        "serializationLibrary" to "jackson",
        "useJakartaEe" to "true",
        "dateLibrary" to "java8",
        "openApiNullable" to "false",
        "hideGenerationTimestamp" to "true"
    ))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateKeycloakClient") {
    description = "Keycloak Openapi generation"
    generatorName.set("java")
    inputSpec.set("$projectDir/openapi/keycloak-api.yaml")
    outputDir.set(keycloakOpenApiOutputDir.get().asFile.absolutePath)

    apiPackage.set("ru.individuals.api.client.keycloak.generated.api")
    modelPackage.set("ru.individuals.api.client.keycloak.generated.model")
    invokerPackage.set("ru.individuals.api.client.keycloak.generated.invoker")

    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)

    configOptions.set(
        mapOf(
            "library" to "webclient",
            "serializationLibrary" to "jackson",
            "useJakartaEe" to "true",
            "dateLibrary" to "java8",
            "openApiNullable" to "false"
        )
    )
}
