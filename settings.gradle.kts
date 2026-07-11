// settings.gradle.kts
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
        // ① 工程内置离线仓库 — Tangem SDK 的 AAR/JAR + POM(含传递依赖声明)。
        //    换任何机器 clone 即可编译，不依赖本机 ~/.m2。
        maven { url = uri("${rootDir}/offline-repo") }
        google()
        mavenCentral()
        // ② JitPack — 兜底解析 rippleBackground 等历史依赖
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "TangemL2Recovery"
include(":app")
