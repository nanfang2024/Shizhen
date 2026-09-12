import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = releaseSigningPropertiesFile.isFile

if (hasReleaseSigning) {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
        check(!releaseSigningProperties.getProperty(key).isNullOrBlank()) {
            "keystore.properties is missing required property: $key"
        }
    }
}

android {
    namespace = "com.framepick.app"
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        applicationId = "com.framepick.app"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(34)
        }
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/AL2.0",
            "/META-INF/LGPL2.1",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 1.2.0 requires compileSdk 35; 1.0.1 is the compatible stable line for this SDK 34 project.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.rbaucells:ffmpeg-android:1.22") {
        isTransitive = false
    }
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation(files("libs/mockwebserver-4.12.0.jar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
