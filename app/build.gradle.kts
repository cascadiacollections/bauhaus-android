import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    id("jacoco")
}

// Load keystore.properties for local development (CI uses env vars instead)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// F-Droid builds pass -Pbauhaus.applicationIdSuffix=.foss so the F-Droid install
// is a separate app from the Play and nightly builds (different signing key).
// Unset, the applicationId is unchanged.
val applicationIdSuffixOverride = providers.gradleProperty("bauhaus.applicationIdSuffix").orElse("").get()

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
        applicationIdSuffix = applicationIdSuffixOverride
        // Launcher shortcuts need the real package name; see res/xml/shortcuts.xml.
        resValue("string", "bauhaus_application_id", "com.cascadiacollections.bauhaus$applicationIdSuffixOverride")
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Literal values so F-Droid's checkupdates can read them from each tag.
        // Bump both for every release.
        versionCode = 225
        versionName = "1.0.1"

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
            matchingFallbacks += listOf("release")
            isDebuggable = false
            proguardFiles("benchmark-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    flavorDimensions += "mode"
    productFlavors {
        create("foss") {
            dimension = "mode"
        }
        create("full") {
            dimension = "mode"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                )
            }
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        // Suppress opinionated checks handled by Dependabot or intentional SDK choices.
        // GradleDependency is disabled because AGP/lint flags new compileSdk versions
        // (e.g. API 37) before the matching platform is downloadable via sdkmanager,
        // which would otherwise block CI on a transient upstream timing issue.
        //
        // AndroidGradlePluginVersion is disabled for the same reason, and it bites
        // harder: with warningsAsErrors it turns "Google published a new AGP" into a
        // red build on every branch, with no change to this repository. While AGP
        // tracks an alpha that fires every few days. Dependabot already opens the
        // bump; CI does not also need to fail until it is merged.
        //
        // GradleVersion is the same trap for the wrapper. A patch release (9.9.2
        // while the repo is on 9.9.1) fails lintFossDebug on every branch until
        // the wrapper bump lands. Dependabot owns that bump.
        disable += setOf(
            "OldTargetApi",
            "NewerVersionAvailable",
            "ObsoleteSdkInt",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "GradleVersion"
        )
        // Off-by-default checks worth having.
        enable += setOf("StopShip", "WrongThreadInterprocedural", "UnusedIds", "ComposeM2Api")
        checkReleaseBuilds = true
        // SARIF feeds GitHub code scanning (see .github/workflows/build.yml).
        sarifReport = true
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Strip Kotlin's generated null checks from shipping builds only.
//
// These are what turn "a platform API handed us null for a non-null parameter"
// into an immediate, well-located NPE — at response.header(), resolver.insert(),
// and the other platform boundaries this app crosses. Applying them to every
// variant, as the top-level kotlin {} block did, bought a small release win at
// the cost of debug builds failing later and further from the cause.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Release") || name.contains("Benchmark")) {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    // Patched minimums for transitive app dependencies; Gradle can still select newer versions.
    constraints {
        implementation(libs.guava.security)
        implementation(libs.wire.runtime.security)
        implementation(libs.wire.runtime.jvm.security)
    }
    lintChecks(libs.compose.lint.checks)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.profileinstaller)

    // OSS license screen
    implementation(libs.aboutlibraries.compose)

    // Memory leak detection in debug builds (runs in separate process)
    debugImplementation(libs.leakcanary.android)

    // Firebase Crashlytics — full flavor only
    "fullImplementation"(platform(libs.firebase.bom))
    "fullImplementation"(libs.firebase.crashlytics)
    "fullImplementation"(libs.firebase.analytics)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.assertk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.assertk)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Disable google-services and crashlytics tasks for FOSS variants
tasks.configureEach {
    if (name.contains("Foss", ignoreCase = true) &&
        (name.contains("GoogleServices") || name.contains("Crashlytics"))
    ) {
        enabled = false
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    description = "Generates Jacoco coverage reports for the fossDebug variant."
    dependsOn("testFossDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val kotlinClasses = fileTree(
        "${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/fossDebug/compileFossDebugKotlin/classes"
    ) {
        exclude(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.class",
            "**/ui/theme/**",
            "**/*Preview*.class"
        )
    }

    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom("$projectDir/src/main/java")
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("jacoco/testFossDebugUnitTest.exec") }
    )
}
