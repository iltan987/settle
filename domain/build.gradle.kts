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
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") { useJUnitPlatform() }
