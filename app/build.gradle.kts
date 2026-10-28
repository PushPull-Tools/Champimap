// Module principal Champimap. Aucune dépendance réseau : l'appli n'a pas la permission Internet.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fr.pushpull.champimap"
    compileSdk = 35

    defaultConfig {
        // Processeurs ARM uniquement (tous les téléphones Android réels) : les variantes x86
        // ne servent qu'aux émulateurs sur PC. Allège l'APK sans bloquer aucun téléphone.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        applicationId = "fr.pushpull.champimap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Signature : clé fournie par la CI (secrets GitHub). Sans clé, la release reste non signée.
    signingConfigs {
        create("release") {
            val ks = System.getenv("CHAMPIMAP_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("CHAMPIMAP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CHAMPIMAP_KEY_ALIAS")
                keyPassword = System.getenv("CHAMPIMAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("CHAMPIMAP_KEYSTORE")
            // Sans clé (secrets absents) : signature de test, pour que l'APK reste installable.
            signingConfig = if (ks != null && file(ks).exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // Builds reproductibles (F-Droid) : pas de bloc de métadonnées de dépendances signé par Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = false }
    // Compatibilité : si un modèle se trouve encore dans assets/ia/ (compilation locale ancienne),
    // il est stocké tel quel (lecture de sa taille par openFd). Normalement livré par Champimap Connect.
    androidResources { noCompress += "onnx" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    // Captures d'écran automatiques (tests unitaires Robolectric + Roborazzi, lancés par la CI) :
    // rendu natif des écrans sur la JVM, ressources et assets de l'appli disponibles.
    // N'a AUCUN effet sur l'APK (seulement sur la tâche testDebugUnitTest).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "4g"
                // Une JVM neuve par classe de test (un parcours) : aucun état partagé entre parcours.
                it.forkEvery = 1
                it.testLogging { events("passed", "failed", "skipped") }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.coil.compose)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Captures d'écran automatiques (tests seulement, jamais dans l'APK de release).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Activité vide utilisée par les tests Compose (manifeste de debug seulement).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
