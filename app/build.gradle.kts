plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.taboia872.androidassist"
    compileSdk = 36

    // Release signing from env (CI secrets). Same key signs DEBUG builds too
    // so updates install over previous versions without uninstalling.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("AA_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("AA_STORE_PASSWORD")
                keyAlias = System.getenv("AA_KEY_ALIAS")
                keyPassword = System.getenv("AA_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.taboia872.androidassist"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same release key on debug builds: reinstall over existing app
            // keeps data (user request) — no more uninstall/reinstall dance.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.security.crypto)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
}
