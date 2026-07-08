import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 1

dependencies {
    implementation(project(":core"))
    implementation(libs.commons.text)
}
