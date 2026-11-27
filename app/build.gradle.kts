plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    lint { abortOnError = false; checkReleaseBuilds = false }
    namespace = "com.anas.deviceagent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.anas.deviceagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 82
        versionName = "0.8.2"
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

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
