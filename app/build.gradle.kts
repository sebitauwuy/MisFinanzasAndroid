plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "cl.personal.finanzas"
    compileSdk = 35
    defaultConfig {
        applicationId = "cl.personal.finanzas"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.9.3"
    }
    signingConfigs {
        create("permanentRelease") {
            storeFile = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            storeType = "pkcs12"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("permanentRelease")
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    kapt("androidx.room:room-compiler:2.6.1")
}
