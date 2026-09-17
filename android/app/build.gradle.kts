import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Voice commands run offline on Vosk's small English model, bundled so a fresh install can listen straight away.
// One canonical copy is shared with Nolee_Robot and Harness and staged into generated assets at build time, not
// duplicated in the source tree. A missing model fails the build: the APK would install and never understand anything.
val voskModelSource = rootProject.file("../../../Nolee_Robot/vosk-model-en-us-staging")
val voskAssetName = "vosk-model-small-en-us-0.15"
val generatedVoskAssets = layout.buildDirectory.dir("generated/vosk-assets")
val prepareVoskAssets by tasks.registering(Sync::class) {
    from(voskModelSource)
    into(generatedVoskAssets.map { it.dir(voskAssetName) })
    doFirst {
        require(voskModelSource.resolve("am/final.mdl").isFile) {
            "Vosk model missing at $voskModelSource. Extract vosk-model-small-en-us-0.15 there before building."
        }
    }
    // StorageService compares this marker to avoid recopying an unchanged model on every launch.
    doLast {
        generatedVoskAssets.get().file("$voskAssetName/uuid").asFile.writeText("vosk-model-small-en-us-0.15-nolee-1\n")
    }
}

android {
    namespace = "ai.nolee.customlauncher"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.nolee.customlauncher"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["main"].assets.srcDir(generatedVoskAssets)
}

tasks.named("preBuild").configure { dependsOn(prepareVoskAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    testImplementation("junit:junit:4.13.2")
}
