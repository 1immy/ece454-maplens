plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.wisc.maplens.temi"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.wisc.maplens.temi"
        // temi V2 runs Android 6 (API 23), V3 Android 11 (API 30): support both.
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
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
    // temi's robot SDK (Maven Central). Calls newer than the robot's launcher fail quietly.
    implementation("com.robotemi:sdk:1.138.0")
}
