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
            implementation(libs.security.crypto)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.libsodium.kmp.jvm)
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
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""
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
        unitTests.all {
            it.maxHeapSize = "4g"
            it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "SERVER_HTTP_URL", "\"https://where.af0.net\"")
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
