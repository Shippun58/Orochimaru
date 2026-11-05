@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")

        maven("https://artifactory.appodeal.com/appodeal-public/") {
            content { includeGroup("com.arthenica") }
        }
    }
}

rootProject.name = "Sanin"
include(":app")