// Plain-Java app with no library dependencies: only the Android framework APIs are used.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.whoop4.hr"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.whoop4.hr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
