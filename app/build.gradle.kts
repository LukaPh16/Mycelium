plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.mycelium.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.mycelium"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.14.0")   // WebViewAssetLoader for the terrarium web module (CLAUDE.md)
}
