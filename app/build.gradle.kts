import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "edu.wisc.maplens"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.wisc.maplens"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":mapparse"))
    implementation(project(":tracking"))
    implementation(project(":routing"))
    implementation(project(":guidance"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
