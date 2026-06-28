plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fr.simioni.a6700transfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.simioni.a6700transfer"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.6"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "6700transfer"
            keyAlias = System.getenv("KEY_ALIAS") ?: "6700transfer"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "6700transfer"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.exifinterface)
}
