plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("core/src/**/*.kt", "gauge/src/**/*.kt", "app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "max_line_length" to "160",
                // Compose functions and the repository's intentional naming conventions.
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
