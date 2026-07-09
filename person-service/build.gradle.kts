fun version(name: String): String = providers.gradleProperty(name).get()

val testcontainersVersion = version("testcontainers.version")
val testcontainersJunitVersion = version("testcontainers-junit.version")
val logstashLogbackEncoderVersion = version("logstash-logback-encoder.version")
val springdocOpenapiStarterWebfluxUiVersion = version("springdoc-openapi-starter-webflux-ui.version")

plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.openapi.generator")
    id("jacoco")
}

group = project.findProperty("group") as String? ?: "ru.person.service"
version = project.findProperty("version") as String? ?: "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.hibernate.orm:hibernate-envers")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocOpenapiStarterWebfluxUiVersion")

    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersJunitVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
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
    inputSpec.set("$projectDir/openapi/person-service.yaml")
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    ignoreFileOverride.set("$projectDir/openapi/.openapi-generator-ignore")
    apiPackage.set("ru.person.service.generated.api")
    modelPackage.set("ru.person.service.generated.dto")
    invokerPackage.set("ru.person.service.generated")
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
    configOptions.set(
        mapOf(
            "library" to "spring-boot",
            "delegatePattern" to "true",
            "useSpringBoot4" to "true",
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
    named("test") {
        compileClasspath += sourceSets.named("main").get().output
        runtimeClasspath += sourceSets.named("main").get().output
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
}

jacoco {
    toolVersion = "0.8.13"
}

// Сгенерированный код и класс запуска не участвуют в подсчёте покрытия.
val coverageExclusions = listOf(
    "ru/person/service/generated/**",
    "org/openapitools/**",
    "**/PersonServiceApplication*"
)

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
