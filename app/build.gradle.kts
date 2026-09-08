plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing identity v2 — a fresh PKCS12 keystore generated 2026-09-08
// (alias ai-cloud-release-v2). CI decodes the RELEASE_KEYSTORE_BASE64_V2 secret
// into $RUNNER_TEMP and exposes the path via RELEASE_STORE_FILE; the other
// three come straight from the _V2 secrets. The pre-v2 secret names are
// intentionally never read by this build — the old identity is retired.
val releaseSigning = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD_V2",
    "RELEASE_KEY_ALIAS_V2",
    "RELEASE_KEY_PASSWORD_V2",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val hasReleaseSigning = releaseSigning.values.all { it != null }
require(releaseSigning.values.all { it == null } || hasReleaseSigning) {
    "Release signing is incomplete. Set all four RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD_V2, RELEASE_KEY_ALIAS_V2 and RELEASE_KEY_PASSWORD_V2 variables, or leave all unset for an unsigned APK."
}

android {
    namespace = "dev.repochat"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.repochat"
        minSdk = 24
        targetSdk = 35
        // Bumped for the Ai Cloud redesign release; upgrades in place via
        // `adb install -r` from the previous v2-identity build (versionCode 2).
        versionCode = 3
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigning["RELEASE_STORE_FILE"]))
                storeType = "pkcs12"
                storePassword = releaseSigning["RELEASE_STORE_PASSWORD_V2"]
                keyAlias = releaseSigning["RELEASE_KEY_ALIAS_V2"]
                keyPassword = releaseSigning["RELEASE_KEY_PASSWORD_V2"]
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                // v2 PKCS12 identity — never the debug keystore, never the
                // retired pre-v2 key.
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
