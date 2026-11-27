plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val ksFile = System.getenv("USTAM_KEYSTORE_FILE")

android {
    namespace = "uz.ustam.sms"
    compileSdk = 34

    defaultConfig {
        applicationId = "uz.ustam.sms"
        minSdk = 23
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("ustam") {
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("USTAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("USTAM_KEY_ALIAS")
                keyPassword = System.getenv("USTAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null && file(ksFile).exists()) signingConfigs.getByName("ustam") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
