import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.vanta.app"
    compileSdk = 37
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        fun quotedBuildValue(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val nimApiKey = System.getenv("NVIDIA_API_KEY") ?: ""
        val fishApiKey = System.getenv("FISH_API_KEY") ?: ""
        val fishVoiceId = System.getenv("FISH_VOICE_ID") ?: ""
        buildConfigField("String", "NVIDIA_API_KEY", quotedBuildValue(nimApiKey))
        buildConfigField("String", "FISH_AUDIO_API_KEY", quotedBuildValue(fishApiKey))
        buildConfigField("String", "FISH_AUDIO_REFERENCE_ID", quotedBuildValue(fishVoiceId))
        applicationId = "com.vanta.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
