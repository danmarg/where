import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().also { props ->
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
    }

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                    if (name.contains("Test")) {
                        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
                    }
                }
            }
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.security.crypto)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.sqldelight.android)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.libsodium.kmp.jvm)
            implementation(libs.moko.resources)
            implementation(libs.compose.ui.test.junit4)
            implementation("app.cash.turbine:turbine:1.1.0")
        }
    }
}

android {
    namespace = "net.af0.where"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.af0.where"
        minSdk = 26
        targetSdk = 35
        versionCode = 104
        versionName = "2026.07.04.1"
    }

    dependenciesInfo {
        // AGP's "Dependency metadata" signing block breaks F-Droid's Reproducible Builds
        // verification, which requires the signature block to contain nothing but the
        // actual signature.
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "4g"
            it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
        }
    }

    val ksFile = System.getenv("KEYSTORE_FILE") ?: System.getProperty("KEYSTORE_FILE")
    val ksPassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getProperty("KEYSTORE_PASSWORD")
    val kPassword = System.getenv("KEY_PASSWORD") ?: System.getProperty("KEY_PASSWORD")
    val hasSigningSecrets = ksFile != null && ksPassword != null && kPassword != null

    if (hasSigningSecrets) {
        signingConfigs {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = "where"
                keyPassword = kPassword
            }
        }
    }

    flavorDimensions += listOf("activityRecognition", "store")

    productFlavors {
        create("standard") {
            dimension = "activityRecognition"
            buildConfigField("Boolean", "ACTIVITY_RECOGNITION_ENABLED", "false")
        }
        create("full") {
            dimension = "activityRecognition"
            buildConfigField("Boolean", "ACTIVITY_RECOGNITION_ENABLED", "true")
        }
        create("gms") {
            dimension = "store"
            manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
        }
        create("fdroid") {
            dimension = "store"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "SERVER_HTTP_URL", "\"https://where-api.af0.net\"")
        }
        debug {
            buildConfigField(
                "String",
                "SERVER_HTTP_URL",
                "\"${localProperties.getProperty("SERVER_HTTP_URL") ?: "http://10.0.2.2:8080"}\"",
            )
        }
    }
}

// Disable fullFdroid variants: full flavor requires activity recognition which needs GMS.
// Use productFlavors pair matching rather than a string check so dimension reordering
// doesn't silently produce a broken APK.
androidComponents {
    beforeVariants { variantBuilder ->
        val flavors = variantBuilder.productFlavors.map { it.second }.toSet()
        if ("full" in flavors && "fdroid" in flavors) {
            variantBuilder.enable = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.accompanist.permissions)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.moko.resources.compose)
    implementation(libs.work.runtime.ktx)

    // GMS-only (Play Store flavor)
    "gmsImplementation"(libs.maps.compose)
    "gmsImplementation"(libs.play.services.location)
    "gmsImplementation"(libs.kotlinx.coroutines.play.services)

    // F-Droid only
    "fdroidImplementation"(libs.maplibre)

    debugImplementation(libs.compose.ui.test.manifest)
}
