plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The public chat token of a TEST account, read from a file named by ORYKSA_TV_TOKEN_FILE (never on the
// command line). Without it the demo runs on the SIMULATED backend.
val tvToken: String = System.getenv("ORYKSA_TV_TOKEN_FILE")?.let { p -> file(p).takeIf { it.isFile }?.readText()?.trim() } ?: ""

android {
    namespace = "com.oryksa.example.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.oryksa.example.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "ORYKSA_TV_CHAT_TOKEN", "\"$tvToken\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            // A test app: signed with the debug key so it can be installed by adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":oryksa"))
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
