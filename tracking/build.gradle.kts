import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "edu.wisc.maplens.tracking"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    api(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    // Lets tests replay real fixtures (e.g. the EH3 graph) without fragile relative paths.
    systemProperty("maplens.fixtures", rootProject.file("data/fixtures").absolutePath)
    // Optional: a folder of scans pulled from the phone, replayed by OrientScanReplayTest.
    System.getenv("MAPLENS_SCANS")?.let { systemProperty("maplens.scans", it) }
}

tasks.withType<Test>().configureEach {
    // Lets replay tests read data/fixtures (maps, recorded traces) without fragile relative paths.
    systemProperty("maplens.fixtures", rootProject.file("data/fixtures").absolutePath)
}
