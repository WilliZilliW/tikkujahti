// The browser version for GitHub Pages: three.js rendering, WebXR AR on Android Chrome, a plain 3D room
// for every other device, and the stick-figure lab (labra.html).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "tikkujahti.js"
            }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation(npm("three", libs.versions.three.get()))
        }
    }
}
