plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.tangeml2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tangeml2"
        minSdk = 24   // Tangem SDK requires >= 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    compileOptions {
        // Java 11 for Web3j's java.time usage; desugaring covers minSdk < 26
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    // The distributable APK is a *minified* release build. The debug build keeps
    // every class (including ~11k unused Material icons and web3j's entire
    // transitive tree) and weighs ~32 MB; R8 brings the release build to ~5.9 MB.
    // It is signed with the debug keystore on purpose: this project ships an
    // openly reviewable, sideloadable APK rather than a Play Store artifact.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // ── Tangem NFC hardware-wallet SDK (vendored in offline-repo/) ──
    implementation("com.tangem.tangem-sdk-kotlin:core:3.9.2")
    implementation("com.tangem.tangem-sdk-kotlin:android:3.9.2")

    // ── Ethereum transaction building / signing primitives ──
    implementation(libs.web3j.core)

    // ── AndroidX ──
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Jetpack Compose UI ──
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.android)
    implementation(libs.json)

    // QR scanning — self-contained, no Google Play Services dependency
    implementation(libs.zxing.embedded)

    // Java 8+ desugaring for minSdk 24 (Web3j's java.time.*)
    coreLibraryDesugaring(libs.desugar)

    // JVM unit tests for the money-critical logic (units, calldata, EIP-155 signing).
    testImplementation(libs.junit)
    // Guarantee a real org.json implementation on the unit-test classpath instead of
    // the stubbed android.jar version.
    testImplementation(libs.json)
}
