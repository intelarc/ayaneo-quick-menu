// Kotlin is compiled by AGP 9's built-in Kotlin support (no kotlin-android plugin needed).
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: shares AYANEO RGB Control's keystore (../AmbientRGB), so both apps are signed by the same
// key. keystore.properties + the keystore are never committed; without them the release build is unsigned.
val signingDir = listOf(rootProject.projectDir, rootProject.projectDir.resolve("../AmbientRGB"))
    .firstOrNull { it.resolve("keystore.properties").exists() }
val signing = Properties().apply {
    signingDir?.resolve("keystore.properties")?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.ayaquick"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rgbplus.ayaneo.quickmenu"
        minSdk = 30          // AYANEO Pocket Air Mini ships Android 11
        // 28 on purpose: Android only lets apps that target 9 or lower switch Wi-Fi on and off directly.
        targetSdk = 28
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (signingDir != null && signing.containsKey("storeFile")) create("release") {
            storeFile = signingDir.resolve(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "ExpiredTargetSdkVersion"      // see targetSdk above
        checkReleaseBuilds = false
    }
}
