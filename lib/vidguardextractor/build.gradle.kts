plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation("org.mozilla:rhino:1.7.14")
}
