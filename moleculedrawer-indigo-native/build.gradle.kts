plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 使用预编译的 Indigo .so（来自官方 Indigo aarch64 库）
android {
    namespace = "com.moldraw.app.indigo_native"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {}
