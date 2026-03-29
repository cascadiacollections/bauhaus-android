import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Load keystore.properties for local development (CI uses env vars instead)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.cascadiacollections.bauhaus"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            val alias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                ?: keystoreProperties["keyAlias"]?.toString()
            val keyPwd = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                ?: keystoreProperties["keyPassword"]?.toString()
            val storePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { file(it) }
                ?: keystoreProperties["storeFile"]?.toString()?.let { rootProject.file(it) }
            val storePwd = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                ?: keystoreProperties["storePassword"]?.toString()
            if (alias != null && keyPwd != null && storePath != null && storePwd != null) {
                keyAlias = alias
                keyPassword = keyPwd
                storeFile = storePath
                storePassword = storePwd
            }
        }
    }

    defaultConfig {
        applicationId = "com.cascadiacollections.bauhaus"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigningConfig = signingConfigs.getByName("release")
            if (releaseSigningConfig.storeFile != null) signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/kotlin/**"
            excludes += "/DebugProbesKt.bin"
            excludes += "/*.txt"
            excludes += "/*.properties"
        }
        // Use native libraries compression for smaller APK, faster load on Android 6+
        jniLibs {
            useLegacyPackaging = false
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
