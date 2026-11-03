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
        versionCode = 30202
        versionName = "3.2.2"
    }

    buildTypes {
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
