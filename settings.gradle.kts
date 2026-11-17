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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "VCamera Space"

include(":app")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":core:media")
include(":core:navigation")
include(":core:permissions")
include(":core:security")
include(":virtual-engine:api")
include(":virtual-engine:impl")
include(":feature:splash")
include(":feature:onboarding")
include(":feature:home")
include(":feature:spaces")
include(":feature:clone")
include(":feature:camera")
include(":feature:advance")
include(":feature:premium")
include(":feature:googleframework")
include(":feature:privacy")
include(":feature:performance")
include(":feature:backup")
include(":feature:settings")
include(":feature:about")
