plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fmc.walkie"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fmc.sentry"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "5.0"
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
}
