plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.nudge"
    compileSdk = 35

    defaultConfig {
        // Same id as the watch app: the Wear Data Layer only pairs apps with matching ids.
        applicationId = "app.nudge"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The S22 Ultra and Galaxy Watch Ultra are arm64. Pass -Pnudge.emulator to add x86_64 for emulators.
        ndk {
            abiFilters += "arm64-v8a"
            if (providers.gradleProperty("nudge.emulator").isPresent) abiFilters += "x86_64"
        }
    }

    // The speech model isn't in the APK: tools/install.ps1 copies it to the phone once.
    sourceSets["main"].java.srcDir("../shared/speech")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../shared/proguard-rules.pro")
            // Personal sideload build: signed with the local debug key so phone and watch match.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("../third_party/sherpa-onnx-1.13.8.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)
    implementation(libs.play.wearable)
    implementation(libs.play.location)
    // Play services pull in an old Fragment; activity-result APIs need 1.3+.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
