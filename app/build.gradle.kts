plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.frank.geminilite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frank.geminilite"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Use the same debug identity as Android Studio / the standard SDK tools.
    // This keeps in-place `adb install -r` updates possible (and the WebView session alive).
    signingConfigs {
        getByName("debug") {
            val standardDebugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (standardDebugKeystore.exists()) {
                storeFile = standardDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
}
