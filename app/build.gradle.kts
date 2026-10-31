plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 back-stack keys are @Serializable so they survive process death.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktfmt)
    // Room 3 generates its code with KSP only.
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// Google recommends a Java toolchain; it also supplies sourceCompatibility/targetCompatibility,
// and built-in Kotlin derives its JVM target from those (developer.android.com/build/jdks).
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

ktfmt { kotlinLangStyle() }

// Exported schemas are committed: they document each version and drive auto-migrations.
room3 { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "com.iltan.settle"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.iltan.settle"
        // Built for one Android 17 phone; 35 leaves room for other recent phones later.
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Installs next to the release app and gets its own Drive sandbox (ADR-008).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // Release signing and shrinking are configured when the first release build is needed.
    }

    androidResources {
        // Lists English and Turkish in the system's per-app language settings.
        generateLocaleConfig = true
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            // Robolectric needs merged resources to run Compose tests as local tests.
            isIncludeAndroidResources = true
            // On JDK 17+ Robolectric needs these opens to reach JDK internals; list copied
            // from https://robolectric.org/getting-started/ (Robolectric 4.17).
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
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    implementation(project(":domain"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    testImplementation(testFixtures(project(":domain")))
    testImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    // Drive REST client (ADR-010).
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    // Task.await(); pinned to the app's coroutines version over the 1.8.0 Play services brings.
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.exifinterface)
    ksp(libs.androidx.room3.compiler)

    // Local UI tests: Compose test rules (JUnit 4) on Robolectric. The test manifest declares the
    // empty activity that createComposeRule() launches.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
