pluginManagement {
    val quarkusPluginVersion: String by settings
    val quarkusPluginId: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id(quarkusPluginId) version quarkusPluginVersion
        id("io.quarkus.extension") version quarkusPluginVersion
    }
}

dependencyResolutionManagement {
    repositories.addAll(pluginManagement.repositories)
}


rootProject.name="quarkus-docker-compose"

include(":integration-test")
include(":runtime")
include(":deployment")
