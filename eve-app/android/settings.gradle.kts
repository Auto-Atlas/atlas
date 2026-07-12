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
        // Meta Wearables Device Access Toolkit (DAT) lives on GitHub Packages, token-gated. Enable
        // alongside the commented dependencies in app/build.gradle.kts. The credentials come from
        // local.properties `github_token` (or the GITHUB_TOKEN env var) — never hardcoded:
        //   maven {
        //       url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
        //       credentials {
        //           val props = java.util.Properties().apply {
        //               file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        //           }
        //           username = props.getProperty("github_user") ?: System.getenv("GITHUB_ACTOR") ?: ""
        //           password = props.getProperty("github_token") ?: System.getenv("GITHUB_TOKEN") ?: ""
        //       }
        //   }
    }
}

rootProject.name = "Atlas"
include(":app")
include(":shared")
include(":wear")
