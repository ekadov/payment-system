pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("org.springframework.boot") version "3.5.14"
        id("io.spring.dependency-management") version "1.1.7"
        id("org.openapi.generator") version "7.20.0"
    }
}

rootProject.name = "individuals-api"
