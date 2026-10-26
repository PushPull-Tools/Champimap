// Module « Champimap Connect » : SEUL composant autorisé à aller sur Internet.
// Il ne connaît que des zones larges (départements, carrés de ~25 km), jamais les spots.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.pushpull.champimap.connect"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.pushpull.champimap.connect"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Même clé que l'appli principale : obligatoire pour la permission « signature » partagée.
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("CHAMPIMAP_KEYSTORE")
            // Sans clé (secrets absents) : signature de test, pour que l'APK reste installable.
            signingConfig = if (ks != null && file(ks).exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
