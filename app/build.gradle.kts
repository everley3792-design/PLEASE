plugins {
    id("com.android.application")
}

android {
    namespace = "com.ocshimeji.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ocshimeji.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 30300
        versionName = "3.3.0"
    }

    signingConfigs {
        // Shared debug key (password "android"): debug APKs from any machine or
        // GitHub Actions run can be installed over each other without losing data.
        // Never use it for release builds.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.15.0")
}
