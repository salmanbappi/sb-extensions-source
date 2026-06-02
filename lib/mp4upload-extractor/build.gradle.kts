plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
