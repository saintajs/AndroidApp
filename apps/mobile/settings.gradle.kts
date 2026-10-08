// MiniSoporte — Android app root settings.
// This Gradle project lives inside the /workspace uv workspace but is NOT a uv member:
// it is managed exclusively by the Gradle wrapper (8.14) pinned in gradle/wrapper.
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "minisoporte"

include(":app")
