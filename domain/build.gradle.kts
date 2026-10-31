// Pure Kotlin: sync, verification and deletion rules live here so they can be tested on the
// JVM without Android. No Android dependency may ever be added to this module (ADR-007).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
    `java-library`
    // The fake Drive, originals and store, shared with the app's tests of the backup runner.
    `java-test-fixtures`
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

ktfmt { kotlinLangStyle() }

dependencies {
    // Suspending ports and backoff delays; JSON for the Drive file description (ADR-003).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") { useJUnitPlatform() }
