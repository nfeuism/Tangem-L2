// Build-environment shim (NOT part of the app itself).
//
// Routes dependency resolution through build-env/maven_proxy.py, which fronts
// several mirrors of Google Maven / Maven Central, retries the intermittently
// flaky upstreams and caches every artifact on disk.
//
// Usage:  ./gradlew -I build-env/init.gradle.kts :app:assembleDebug
val proxy = "http://127.0.0.1:8899"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.proxyRepos() {
    maven {
        name = "proxyCentral"
        url = uri("$proxy/central")
        isAllowInsecureProtocol = true
        content { excludeGroupByRegex("com\\.tangem\\.tangem-sdk-kotlin.*") }
    }
    maven {
        name = "proxyGoogle"
        url = uri("$proxy/gmaven")
        isAllowInsecureProtocol = true
        content { excludeGroupByRegex("com\\.tangem\\.tangem-sdk-kotlin.*") }
    }
    maven {
        name = "proxyJitpack"
        url = uri("$proxy/jitpack")
        isAllowInsecureProtocol = true
        content { excludeGroupByRegex("com\\.tangem\\.tangem-sdk-kotlin.*") }
    }
}

beforeSettings {
    pluginManagement.repositories.proxyRepos()
    dependencyResolutionManagement.repositories.proxyRepos()
}
