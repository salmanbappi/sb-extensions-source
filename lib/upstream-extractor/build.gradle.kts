plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
