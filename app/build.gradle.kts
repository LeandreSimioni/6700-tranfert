plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.simioni.a6700transfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.simioni.a6700transfer"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.14"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "6700transfer"
            keyAlias = System.getenv("KEY_ALIAS") ?: "6700transfer"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "6700transfer"
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { signingConfig = signingConfigs.getByName("fixed"); isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
