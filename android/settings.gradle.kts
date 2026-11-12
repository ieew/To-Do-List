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

rootProject.name = "todo-android"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:network")
include(":core:sync")
include(":core:designsystem")
include(":core:ai")
include(":core:reminder")
include(":feature:tasks")
include(":feature:detail")
include(":feature:calendar")
include(":feature:stats")
include(":feature:settings")
include(":feature:members")
include(":feature:ai")
include(":feature:widget")
