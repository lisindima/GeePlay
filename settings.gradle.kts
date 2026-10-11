pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":common")
include(":mobile")
include(":automotive")
include(":shared")
include(":vehicle-geely")
include(":geely")
include(":evccapi-contract")
project(":evccapi-contract").projectDir = file("third_party/evccapi/evccapi-contract")
include(":evccapi-client")
project(":evccapi-client").projectDir = file("third_party/evccapi/evccapi-client")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
