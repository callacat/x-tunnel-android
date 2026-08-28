plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val appVersionCode = providers.environmentVariable("XTUNNEL_ANDROID_VERSION_CODE")
    .map(String::toInt)
    .orElse(1)
val appVersionName = providers.environmentVariable("XTUNNEL_ANDROID_VERSION_NAME")
    .orElse("0.1.0-round8")

android {
    namespace = "com.xtunnel.android"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.xtunnel.android"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isPresent) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.orNull
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
