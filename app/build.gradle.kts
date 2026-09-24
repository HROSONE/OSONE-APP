plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.osone.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.osone.app"
        minSdk = 26
        targetSdk = 36
        // A CI publica cada versão com um número crescente; localmente vale o padrão.
        versionCode = System.getenv("OSTIE_VERSION_CODE")?.toIntOrNull() ?: 15
        versionName = System.getenv("OSTIE_VERSION_NAME") ?: "0.15.0"
    }
    signingConfigs {
        create("stable") {
            val keystorePath = System.getenv("OSTIE_SIGNING_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OSTIE_SIGNING_PASSWORD")
                keyAlias = System.getenv("OSTIE_SIGNING_ALIAS") ?: "ostie"
                keyPassword = System.getenv("OSTIE_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("OSTIE_SIGNING_KEYSTORE_FILE").isNullOrBlank())
                signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
