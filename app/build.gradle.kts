import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

// CI passes these in; local/IDE builds fall back to fixed defaults.
val ciVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0-dev"

// Fixed keystore decoded from GitHub Secrets by the workflow. When it is absent
// (fork PRs, secrets not configured yet) every build type uses the debug key.
val keystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull
    ?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "io.github.datthanhdoan.paperscreen"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.datthanhdoan.paperscreen"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("fixed") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.material)

    testImplementation(libs.junit)
}
