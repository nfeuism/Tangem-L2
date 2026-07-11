// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.tangeml2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tangeml2"
        minSdk = 24   // Tangem SDK requires >= 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        // Java 11 for Web3j's java.time usage; desugar below handles minSdk < 26
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    // ═══════════════════════════════════════════════════════════════
    // Tangem SDK — from Maven Local (built from ~/android-toolchain/tangem-sdk-src)
    // ═══════════════════════════════════════════════════════════════
    implementation("com.tangem.tangem-sdk-kotlin:core:3.9.2")
    implementation("com.tangem.tangem-sdk-kotlin:android:3.9.2")

    // ═══════════════════════════════════════════════════════════════
    // Web3j — transaction building & signing recovery
    // ═══════════════════════════════════════════════════════════════
    implementation(libs.web3j.core)

    // ═══════════════════════════════════════════════════════════════
    // AndroidX + Kotlin (mostly provided transitively by Tangem SDK,
    // but declared explicitly so they resolve correctly)
    // ═══════════════════════════════════════════════════════════════
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.json)

    // 二维码扫描（收款地址）— 自包含，不依赖 Google Play，中国网络友好
    implementation(libs.zxing.embedded)

    // Java 8+ desugaring for minSdk 24 (Web3j's java.time.*)
    coreLibraryDesugaring(libs.desugar)
}
