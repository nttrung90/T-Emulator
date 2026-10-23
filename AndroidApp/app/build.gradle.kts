plugins {
    id("com.android.application")
    id("com.android.legacy-kapt")
}

android {
    namespace = "com.temulator.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.temulator.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }

        androidResources.localeFilters += listOf("en", "vi")

        val webClientId = project.findProperty("GOOGLE_WEB_CLIENT_ID")?.toString()
            ?: System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""
        val backendUrl = project.findProperty("BACKEND_VERIFY_URL")?.toString()
            ?: System.getenv("BACKEND_VERIFY_URL") ?: ""
        val firebaseApiKey = project.findProperty("FIREBASE_API_KEY")?.toString()
            ?: System.getenv("FIREBASE_API_KEY") ?: ""
        val firebaseAppId = project.findProperty("FIREBASE_APPLICATION_ID")?.toString()
            ?: System.getenv("FIREBASE_APPLICATION_ID") ?: ""
        val firebaseProjectId = project.findProperty("FIREBASE_PROJECT_ID")?.toString()
            ?: System.getenv("FIREBASE_PROJECT_ID") ?: ""

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "BACKEND_VERIFY_URL", "\"$backendUrl\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")

        manifestPlaceholders["GOOGLE_WEB_CLIENT_ID"] = webClientId
        manifestPlaceholders["BACKEND_VERIFY_URL"] = backendUrl

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -fno-char8_t -O3 -fomit-frame-pointer"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_LDFLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }

    signingConfigs {
        create("release") {
            val keyStorePath = project.findProperty("RELEASE_STORE_FILE")?.toString() ?: System.getenv("RELEASE_STORE_FILE")
            val keyStorePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString() ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyStoreAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString() ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyStoreKeyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString() ?: System.getenv("RELEASE_KEY_PASSWORD")
            if (keyStorePath != null && file(keyStorePath).exists() && keyStorePassword != null && keyStoreAlias != null && keyStoreKeyPassword != null) {
                storeFile = file(keyStorePath)
                storePassword = keyStorePassword
                keyAlias = keyStoreAlias
                keyPassword = keyStoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.matching { it.name.startsWith("kapt") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")

    // Credential Manager & Google ID
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Firebase Auth & Identity
    implementation("com.google.firebase:firebase-auth:22.3.1")

    // Google Play Billing
    implementation("com.android.billingclient:billing:8.0.0")

    // WorkManager & Background transfer
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (for Drive REST v3 & Backend API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
}

tasks.register("validateReleaseConfig") {
    group = "verification"
    description = "Validates that required release credentials and URLs are configured without placeholders."

    val skipValidationProvider = providers.gradleProperty("SKIP_RELEASE_CONFIG_VALIDATION")
        .orElse(providers.environmentVariable("SKIP_RELEASE_CONFIG_VALIDATION"))
        .orElse(providers.gradleProperty("ALLOW_INSECURE_RELEASE_BUILD"))
        .orElse(providers.environmentVariable("ALLOW_INSECURE_RELEASE_BUILD"))

    val webClientIdProvider = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID")
        .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID"))

    val backendUrlProvider = providers.gradleProperty("BACKEND_VERIFY_URL")
        .orElse(providers.environmentVariable("BACKEND_VERIFY_URL"))

    val firebaseApiKeyProvider = providers.gradleProperty("FIREBASE_API_KEY")
        .orElse(providers.environmentVariable("FIREBASE_API_KEY"))

    val firebaseAppIdProvider = providers.gradleProperty("FIREBASE_APPLICATION_ID")
        .orElse(providers.environmentVariable("FIREBASE_APPLICATION_ID"))

    val firebaseProjectIdProvider = providers.gradleProperty("FIREBASE_PROJECT_ID")
        .orElse(providers.environmentVariable("FIREBASE_PROJECT_ID"))

    doLast {
        val skipValidation = skipValidationProvider.orNull?.toBoolean() == true
        if (skipValidation) {
            logger.warn("WARNING: Release configuration validation was skipped (SKIP_RELEASE_CONFIG_VALIDATION=true). Required production credentials are missing or placeholders. Do NOT publish this build to production.")
            return@doLast
        }

        val missingOrInvalid = mutableListOf<String>()

        val webClientId = webClientIdProvider.orNull ?: ""
        if (webClientId.isBlank() || webClientId.startsWith("YOUR_") || webClientId.contains("YOUR_GOOGLE_WEB_CLIENT_ID")) {
            missingOrInvalid.add("GOOGLE_WEB_CLIENT_ID")
        }

        val backendUrl = backendUrlProvider.orNull ?: ""
        if (backendUrl.isBlank() || backendUrl.startsWith("YOUR_") || !backendUrl.startsWith("https://", ignoreCase = true)) {
            missingOrInvalid.add("BACKEND_VERIFY_URL (must be HTTPS URL)")
        }

        val firebaseApiKey = firebaseApiKeyProvider.orNull ?: ""
        if (firebaseApiKey.isBlank() || firebaseApiKey.startsWith("YOUR_")) {
            missingOrInvalid.add("FIREBASE_API_KEY")
        }

        val firebaseAppId = firebaseAppIdProvider.orNull ?: ""
        if (firebaseAppId.isBlank() || firebaseAppId.startsWith("YOUR_")) {
            missingOrInvalid.add("FIREBASE_APPLICATION_ID")
        }

        val firebaseProjectId = firebaseProjectIdProvider.orNull ?: ""
        if (firebaseProjectId.isBlank() || firebaseProjectId.startsWith("YOUR_")) {
            missingOrInvalid.add("FIREBASE_PROJECT_ID")
        }

        if (missingOrInvalid.isNotEmpty()) {
            throw GradleException(
                "Release configuration validation failed. The following required keys are missing or invalid: " +
                missingOrInvalid.joinToString(", ") +
                ". Values are redacted for security. Configure these via gradle.properties or environment variables before release build, or set SKIP_RELEASE_CONFIG_VALIDATION=true for test builds."
            )
        }
        logger.lifecycle("Release configuration validation passed: all required keys are present.")
    }
}

tasks.matching { it.name in listOf("assembleRelease", "bundleRelease") }.configureEach {
    dependsOn("validateReleaseConfig")
}
