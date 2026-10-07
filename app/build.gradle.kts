import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.sentry)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace  = "com.vinmusic"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.vinmusic"
        minSdk          = 26
        targetSdk       = 35
        versionCode     = 23
        versionName     = "2.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFileProp = localProperties.getProperty("RELEASE_STORE_FILE") ?: System.getenv("RELEASE_STORE_FILE")
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isCrunchPngs = false
            // Publishing a debug-signed release breaks upgrade paths. Fail the
            // release build when signing is not configured instead of silently
            // producing an installable-but-unpublishable APK.
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

sentry {
    // Disable proguard mapping uploads on local dev builds to avoid requiring Sentry Auth Token secrets.
    autoUploadProguardMapping.set(false)
    includeSourceContext.set(false)
}

configurations.all {
    resolutionStrategy {
        // Force specific protobuf version to resolve conflict between Media3 and Firebase dependencies
        // which rely on different, incompatible protobuf-javalite versions. Can be removed once aligned.
        force("com.google.protobuf:protobuf-javalite:3.25.1")
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.animation)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.common)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coil (image loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Palette (dynamic coloring)
    implementation(libs.androidx.palette.ktx)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Gson
    implementation(libs.gson)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Jsoup (lyrics scraping)
    implementation(libs.jsoup)

    // NewPipeExtractor — proper YouTube stream extraction
    implementation(libs.newpipe.extractor)

    // Ktor (KuGou etc)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content)
    implementation(libs.ktor.serialization)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.config)
    implementation(libs.play.services.auth)


    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)

    // Sentry
    implementation(libs.sentry.android)
    implementation(libs.sentry.compose)

    // TarsosDSP
    implementation(libs.tarsos.core)

    // Haze (Real Frosted Glass Backdrop Blur)
    implementation("dev.chrisbanes.haze:haze:1.3.1")

    // EncryptedSharedPreferences (YT session cookie storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
