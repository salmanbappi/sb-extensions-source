import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 10

dependencies {
    implementation(project(":lib:episode-metadata-fetcher"))
}
