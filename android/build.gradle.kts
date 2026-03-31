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
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
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
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""
        buildConfigField(
            "String",
            "SERVER_WS_URL",
            "\"${localProperties.getProperty("SERVER_WS_URL") ?: "ws://10.0.2.2:8080/ws"}\"",
        )
        buildConfigField(
            "String",
            "SERVER_HTTP_URL",
            "\"${localProperties.getProperty("SERVER_HTTP_URL") ?: "http://10.0.2.2:8080"}\"",
        )
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
        unitTests.isIncludeAndroidResources = false
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE") ?: System.getProperty("KEYSTORE_FILE") ?: ""
            val ksPassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getProperty("KEYSTORE_PASSWORD") ?: ""
            val kPassword = System.getenv("KEY_PASSWORD") ?: System.getProperty("KEY_PASSWORD") ?: ""

            if (ksFile.isNotEmpty() && ksPassword.isNotEmpty() && kPassword.isNotEmpty()) {
                storeFile = file(ksFile)
                storePassword = ksPassword
                keyAlias = "where"
                keyPassword = kPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.maps.compose)
    implementation(libs.play.services.location)
    implementation(libs.accompanist.permissions)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
}
