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
        // 液态玻璃（iOS 26 风格）目前只通过 JitPack 分发。
        // 加 content 过滤，避免它被拿去解析所有依赖 —— JitPack 对未知构件
        // 的响应是"按需构建"，会让正常的依赖解析变慢。
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.QWEA0")
            }
        }
    }
}

rootProject.name = "Kipkip2"
include(":app")
 