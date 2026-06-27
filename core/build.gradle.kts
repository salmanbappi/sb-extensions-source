plugins {
    id("com.android.library")
    id("kotlinx-serialization")
}

android {
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    namespace = "extensions.core"

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.srcDirs("src/main/res")
        }
    }

    buildFeatures {
        resValues = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(11)
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
