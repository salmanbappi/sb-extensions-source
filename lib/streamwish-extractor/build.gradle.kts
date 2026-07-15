plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:synchrony"))
}
