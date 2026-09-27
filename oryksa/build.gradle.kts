import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.oryksa.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()
    coordinates("com.oryksa", "sdk", "1.0.0")
    pom {
        name.set("ORYKSA SDK for Android")
        description.set("Official Android SDK for ORYKSA AI Employees: in-app AI chat (Jetpack Compose) and API v1 client.")
        url.set("https://developer.oryksa.com/en/sdks")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
        organization { name.set("W8 Atlantic Unipessoal Lda"); url.set("https://oryksa.com") }
        developers { developer { id.set("oryksa"); name.set("ORYKSA AI Employees"); email.set("info@oryksa.com") } }
        scm {
            url.set("https://github.com/oryksa/oryksa-android")
            connection.set("scm:git:https://github.com/oryksa/oryksa-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/oryksa/oryksa-android.git")
        }
    }
}
