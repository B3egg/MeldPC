@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        exclusiveContent {
            forRepositories(mavenLocal(), maven("https://jitpack.io"))
            filter { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MeldDesktop"
