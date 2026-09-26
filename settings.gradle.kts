pluginManagement {
    repositories {
        maven { url = uri("${rootDir}/local-m2") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("${rootDir}/local-m2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Obhod"
include(":app")
