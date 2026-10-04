plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.detekt) apply false
}

subprojects {
    plugins.withType<JavaBasePlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }
        }
    }
}

// Static analysis for every module: detekt (+ ktlint formatting and Compose rules), configured
// in config/detekt/detekt.yml (shared with sir-android). Findings that predate a rule live in
// each module's detekt-baseline.xml; new code must be clean. `./gradlew detekt` checks,
// `--auto-correct` applies formatting fixes, `detektBaseline` regenerates baselines.
val detektRulePlugins = listOf(libs.detekt.rules.ktlint.wrapper, libs.compose.rules.detekt)
val detektReportMerge by tasks.registering(dev.detekt.gradle.report.ReportMergeTask::class) {
    output.set(layout.buildDirectory.file("reports/detekt/detekt.sarif"))
}
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        parallel.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(file("detekt-baseline.xml"))
        basePath.set(rootProject.layout.projectDirectory)
        // Every source set, including product flavors (src/foss, src/full) and tests.
        source.setFrom("src", "build.gradle.kts")
    }
    dependencies {
        detektRulePlugins.forEach { "detektPlugins"(it) }
    }
    // One SARIF file for GitHub code scanning (see .github/workflows/build.yml).
    val detekt = tasks.named<dev.detekt.gradle.Detekt>("detekt") { finalizedBy(detektReportMerge) }
    detektReportMerge.configure { input.from(detekt.flatMap { it.reports.sarif.outputLocation }) }
}
