plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.oryksa.example"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.oryksa.example"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"
        // Only for the CI screenshots: a short-lived session token of a TEST account (never the secret key).
        buildConfigField("String", "ORYKSA_TEST_TOKEN", "\"" + (System.getenv("ORYKSA_TEST_TOKEN") ?: "") + "\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":oryksa"))
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
