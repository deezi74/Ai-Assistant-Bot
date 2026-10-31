plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.pocketagent"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.pocketagent"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Native llama.cpp execution is shipped for 64-bit ARM only.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DBUILD_SHARED_LIBS=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions", "-frtti")
                cFlags += listOf("-O3")
            }
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("POCKETAGENT_KEYSTORE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("POCKETAGENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETAGENT_KEY_ALIAS")
                keyPassword = System.getenv("POCKETAGENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("POCKETAGENT_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE.md",
                "/META-INF/NOTICE.md",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":agent-core"))

    // AndroidX core + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Networking: OkHttp3 (SSE streaming, Hugging Face downloads, web search)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    // App lock, SAF folder access
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    // IMAP email connector (app-password sign-in)
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    // Coroutines + JSON
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
