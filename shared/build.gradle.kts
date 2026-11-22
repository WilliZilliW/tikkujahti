// Everything the Android app and the browser version share: the game itself (stick figures, their
// animation and physics, balls, hits) and the geometry the renderers draw. Plain Kotlin, no platform code.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    js {
        // The game logic needs no browser APIs; logic tests run on the JVM (jvmTest).
        browser()
        useEsModules()
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
