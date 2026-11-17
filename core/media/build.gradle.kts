plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vcameraspace.core.media"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":virtual-engine:api"))

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.exoplayer.smoothstreaming)
    api(libs.androidx.media3.exoplayer.rtsp)
    api(libs.androidx.media3.ui)
    api(libs.androidx.media3.common)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
