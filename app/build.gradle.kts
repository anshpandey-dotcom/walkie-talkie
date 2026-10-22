plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fmc.walkie"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "8.0"
    }

    flavorDimensions += "role"
    productFlavors {
        create("camera") {
            dimension = "role"
            applicationId = "com.anshpandey.sentry.camera"
            resValue("string", "app_name", "Camera")
        }
        create("monitor") {
            dimension = "role"
            applicationId = "com.anshpandey.sentry.monitor"
            resValue("string", "app_name", "Monitor")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
