plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.soundlens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.soundlens"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Futuro: base URL para Lambda
        buildConfigField("String", "IDENTIFY_BASE_URL", "\"https://api.example.com/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // ← habilita BuildConfig para permitir buildConfigField
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")

    implementation("com.amazonaws:aws-android-sdk-core:2.66.0")
    implementation("com.amazonaws:aws-android-sdk-s3:2.66.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.amazonaws:aws-android-sdk-lambda:2.66.0")



    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // (Futuro) HTTP client
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // YouTube embebido
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")
}