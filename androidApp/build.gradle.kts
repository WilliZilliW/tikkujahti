// The Android app: ARCore + SceneView draw the game from :shared into the real room.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versions:
//  - Play releases: -PtikkuVersion=1.1.2 (the release workflow takes it from the tag v1.1.2).
//    versionCode = major*10000 + minor*100 + patch, so it always grows with the version.
//  - Test builds: -PtikkuBuild=<run number> from the APK workflow.
//  - Neither: 1 / "1.0".
val tikkuVersion = providers.gradleProperty("tikkuVersion").orNull
val tikkuBuild = providers.gradleProperty("tikkuBuild").orNull

fun versionCodeOf(v: String): Int {
    val parts = v.split('.').map { it.toIntOrNull() ?: throw GradleException("Versio '$v' ei kelpaa: käytä muotoa 1.2 tai 1.2.3") }
    if (parts.size !in 1..3 || parts.drop(1).any { it > 99 }) {
        throw GradleException("Versio '$v' ei kelpaa: käytä muotoa 1.2 tai 1.2.3, toinen ja kolmas osa enintään 99")
    }
    val p = parts + List(3 - parts.size) { 0 }
    return p[0] * 10000 + p[1] * 100 + p[2]
}

// Play release signing: the upload key comes from environment variables (GitHub secrets in the release
// workflow). Without them a release build is unsigned.
val uploadKeystore: String? = System.getenv("TIKKU_KEYSTORE_FILE")

android {
    namespace = "fi.otsosoft.tikkujahti"
    compileSdk = 37

    defaultConfig {
        applicationId = "fi.otsosoft.tikkujahti"
        minSdk = 26
        targetSdk = 36
        versionCode = tikkuVersion?.let(::versionCodeOf) ?: (tikkuBuild?.toInt() ?: 1)
        versionName = tikkuVersion ?: tikkuBuild?.let { "1.0-testi.$it" } ?: "1.0"
    }

    // Test versions (debug) are signed with a fixed key kept in the repo, so updates install over each
    // other on every machine and in GitHub Actions. Its password is Android's public default; Play
    // releases use the upload key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("testiavain.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("TIKKU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TIKKU_KEY_ALIAS")
                keyPassword = System.getenv("TIKKU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.arsceneview)
    implementation(libs.arcore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
