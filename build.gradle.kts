plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// No browser test runner (Karma) anywhere: the game logic is tested on the JVM, and the browser version
// is checked by running it in headless Chromium (tools/kuvat.mjs).
subprojects {
    tasks.matching { it.name == "jsBrowserTest" }.configureEach { enabled = false }
}
