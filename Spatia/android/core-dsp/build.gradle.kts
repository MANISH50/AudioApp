import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Spatia's audio engine, shared by both apps:
 *  - jvm:      consumed by the Android app (android/app)
 *  - mingwX64: built as spatia_dsp.dll for the Windows app (windows/)
 *  - linuxX64: same native code and C API, so the native build can be tested on Linux CI
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    linuxX64 {
        binaries.sharedLib { baseName = "spatia_dsp" }
    }
    mingwX64 {
        binaries.sharedLib { baseName = "spatia_dsp" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
        }
    }
}
