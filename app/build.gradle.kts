plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.obsidianwidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "local.obsidianwidget"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload-only: sign release builds with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
        }
        // As in Paid to Poop: release speed for everyday use on the phone (not debuggable, so
        // Compose runs optimized), signed with the debug key so it installs over the debug build
        // and keeps its data. Install with ./gradlew installFast.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // Keeps resource IDs the same from build to build, pinned to those of an installed build. A
    // launcher keeps showing a widget's last saved layout after an app update until the app
    // redraws it; if the IDs had moved, that layout no longer fits and the widget goes blank.
    // New resources get IDs of their own; to pin them too, regenerate stable-ids.txt from a new
    // APK with "aapt2 dump resources" (one "local.obsidianwidget:type/name = 0x7f..." per line).
    androidResources {
        additionalParameters += listOf("--stable-ids", file("stable-ids.txt").path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
