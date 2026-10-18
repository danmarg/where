import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Custom detekt rules for shared/src/commonMain/.../e2ee (see config/detekt/detekt.yml).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // detekt 1.23 runs these rules on its own Kotlin 2.0 runtime.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
