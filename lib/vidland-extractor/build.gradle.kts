plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(libs.jsunpacker)
    implementation(project(":lib:playlist-utils"))
}
