plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "cl.personal.finanzas"
    compileSdk = 37
    defaultConfig {
        applicationId = "cl.personal.finanzas"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.14"
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
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Haze 2.1.0: desenfoque de la barra de navegación en Android 12+.
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur-materials:2.1.0")
    // Vico: gráfico semanal de gastos.
    implementation("com.patrykandpatrick.vico:compose:3.3.1")
    ksp("androidx.room:room-compiler:2.8.5")
}
