import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.metanav.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metanav.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Meta Wearables Device Access Toolkit. With Developer Mode enabled in the Meta AI app
        // both values can stay "0". For a release-channel build, paste the values from the
        // Wearables Developer Center here (or set META_APP_ID / META_CLIENT_TOKEN env vars).
        manifestPlaceholders["mwdat_application_id"] = System.getenv("META_APP_ID") ?: "0"
        manifestPlaceholders["mwdat_client_token"] = System.getenv("META_CLIENT_TOKEN") ?: "0"

        // Every phone that can pair with Meta glasses is 64-bit ARM; skipping the other ABIs
        // roughly halves the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // TFLite models must not be compressed inside the APK or the interpreter cannot mmap them.
    androidResources { noCompress += listOf("tflite") }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.task.vision)
}
