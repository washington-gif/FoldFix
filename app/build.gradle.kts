plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.washingtongif.foldfix"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.washingtongif.foldfix"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release builds are signed with a key supplied by CI secrets (never committed).
    // Without those secrets (e.g. a fork), release falls back to the debug key.
    val ksPath = System.getenv("FOLDFIX_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("FOLDFIX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOLDFIX_KEY_ALIAS")
                keyPassword = System.getenv("FOLDFIX_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
}
