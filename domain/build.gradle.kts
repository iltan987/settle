// Pure Kotlin: sync, verification and deletion rules live here so they can be tested on the
// JVM without Android. No Android dependency may ever be added to this module (ADR-007).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
    `java-library`
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

ktfmt { kotlinLangStyle() }

dependencies {
    // Suspending ports and backoff delays; JSON for the Drive file description (ADR-003).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") { useJUnitPlatform() }
