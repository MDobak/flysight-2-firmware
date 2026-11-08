plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.flysightstatus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.flysightstatus"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
