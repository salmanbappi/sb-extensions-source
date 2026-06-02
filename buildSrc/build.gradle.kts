plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.android.gradle)
    implementation(libs.kotlin.gradle)
    implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.gradle.get()}")
    implementation(libs.gradle.kotlinter)
}
