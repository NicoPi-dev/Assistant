import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fr.nicopi.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fr.nicopi.assistant"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "CLAUDE_API_KEY",
            "\"${localProps.getProperty("CLAUDE_API_KEY", "")}\""
        )
        buildConfigField("String", "HA_URL", "\"${localProps.getProperty("HA_URL", "")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${localProps.getProperty("HA_TOKEN", "")}\"")
        buildConfigField("String", "PRENOM", "\"${localProps.getProperty("PRENOM", "")}\"")
        buildConfigField("String", "VILLE", "\"${localProps.getProperty("VILLE", "")}\"")
        buildConfigField("String", "FAVORIS", "\"${localProps.getProperty("FAVORIS", "")}\"")
        buildConfigField("String", "SONOS", "\"${localProps.getProperty("SONOS", "")}\"")
        buildConfigField("String", "SPOTIFY_ID", "\"${localProps.getProperty("SPOTIFY_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_SECRET", "\"${localProps.getProperty("SPOTIFY_SECRET", "")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}