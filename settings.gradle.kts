pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://nexus.inductiveautomation.com/repository/public/")
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://nexus.inductiveautomation.com/repository/public/")
        mavenCentral()
    }
}

rootProject.name = "ign-project-module"
include(":gateway")