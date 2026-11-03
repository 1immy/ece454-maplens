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

    sourceSets {
        getByName("main") {
            // Verified map graphs + straightened images, loaded with MapAssets.
            assets.srcDir(rootProject.file("data/fixtures/maps"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The scan-to-orient debug screen loads traced graphs (not the large map images) as assets.
val scanGraphs = tasks.register<Copy>("copyScanGraphs") {
    from(rootProject.file("data/fixtures/maps")) { include("*.graph.json") }
    into(layout.buildDirectory.dir("generated/scanGraphs/graphs"))
}
android.sourceSets.getByName(
    "debug"
).assets.srcDir(layout.buildDirectory.dir("generated/scanGraphs"))
tasks.matching {
    it.name.contains("Debug") &&
        (it.name.endsWith("DebugAssets") || it.name.contains("lint", ignoreCase = true))
}.configureEach { dependsOn(scanGraphs) }

dependencies {
    implementation(project(":mapparse"))
    implementation(project(":tracking"))
    implementation(project(":routing"))
    implementation(project(":guidance"))

    // Scan-to-orient debug screen (ADR 0004). Bundled OCR model: works with no network.
    debugImplementation(libs.androidx.camera.camera2)
    debugImplementation(libs.androidx.camera.lifecycle)
    debugImplementation(libs.androidx.camera.view)
    debugImplementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
