plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:m3u8server"))
}
