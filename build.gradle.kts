// Declaring plugins once here puts them on one shared classpath for all modules.
// AGP 9 compiles Kotlin itself ("built-in Kotlin") and brings Kotlin Gradle Plugin 2.2.10 by
// default; declaring the Kotlin plugins here at the catalog version lifts it to that version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}
