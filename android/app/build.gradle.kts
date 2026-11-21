plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Voix intégrées (Piper) : bibliothèque sherpa-onnx, téléchargée automatiquement au 1er build.
val sherpaVersion = "1.13.7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    logger.lifecycle("Téléchargement de sherpa-onnx $sherpaVersion…")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
}

android {
    namespace = "com.lingualive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lingualive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // Téléphones ARM (quasi tous les Android) : limite la taille de l'APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    packaging {
        jniLibs { pickFirsts += listOf("**/libc++_shared.so") }
        resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signé avec la clé debug pour pouvoir installer directement.
            // Pour le Play Store, remplace par ta propre clé de signature.
            signingConfig = signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Traduction sur l'appareil (gratuite, hors-ligne après téléchargement du modèle)
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Serveur WebSocket intégré au téléphone (mode hors-ligne, Wi-Fi local / point d'accès)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Reconnaissance vocale intégrée, 100 % hors-ligne (Vosk)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Voix intégrées (Piper via sherpa-onnx)
    implementation(files(sherpaAar))
    // Décompression des packs de langue (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
