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
        // Reconhecedor da escuta ativa (Vosk) só para celulares ARM: evita ~20 MB de bibliotecas x86 no APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Login e assinatura (Firebase do OSONE): valores vêm dos segredos da CI. Vazios = planos desligados.
        fun env(name: String) = "\"" + (System.getenv(name) ?: "").replace("\"", "") + "\""
        buildConfigField("String", "FIREBASE_PROJECT_ID", env("OSTIE_FIREBASE_PROJECT_ID"))
        buildConfigField("String", "FIREBASE_APP_ID", env("OSTIE_FIREBASE_APP_ID"))
        buildConfigField("String", "FIREBASE_API_KEY", env("OSTIE_FIREBASE_API_KEY"))
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", env("OSTIE_FIREBASE_WEB_CLIENT_ID"))
        // Servidor da assinatura na Vercel (ex.: https://ostie-api.vercel.app), rotas /api/checkout, /api/portal.
        buildConfigField("String", "OSTIE_API_URL", env("OSTIE_API_URL"))
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
    buildFeatures { compose = true; buildConfig = true }
    // Testes de tela rodam na JVM com Robolectric (sem emulador), dentro de testDebugUnitTest.
    testOptions { unitTests { isIncludeAndroidResources = true } }
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
    // Escuta ativa offline ("Ei, Ostie"); @aar sem dependências transitivas, com o JNA para Android.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Conta OSTIE: login Firebase (Google ou e-mail) e plano da assinatura.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
    // org.json real nos testes JVM (o android.jar só traz stubs).
    testImplementation("org.json:json:20250517")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Falhas de teste mostram a pilha completa no log do CI.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
