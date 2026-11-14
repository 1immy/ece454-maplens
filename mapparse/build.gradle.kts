import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "edu.wisc.maplens.mapparse"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "edu.wisc.maplens.mapparse.PosterParserChecks"
    }

    sourceSets.getByName("androidTest") {
        assets.srcDirs(
            rootProject.file("data/fixtures/maps"),
            rootProject.file("data/fixtures/photos/ehall")
        )
    }

    sourceSets.getByName("main").resources.srcDir(rootProject.file("data/models"))

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
    api(project(":core:model"))
    implementation(libs.opencv)
    // On-device text recognition, model bundled in the APK (no network, no Play services).
    implementation(libs.mlkit.text.recognition)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
