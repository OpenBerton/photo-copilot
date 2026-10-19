plugins {
    id("com.android.application")
}

android {
    namespace = "com.yrf3.photocopilot"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.yrf3.photocopilot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
