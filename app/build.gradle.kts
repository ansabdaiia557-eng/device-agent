plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.anas.deviceagent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.anas.deviceagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("../device-agent.keystore")
            storePassword = "deviceagent"
            keyAlias = "deviceagent"
            keyPassword = "deviceagent"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
