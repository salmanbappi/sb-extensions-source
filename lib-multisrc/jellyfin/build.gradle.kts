import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 2

dependencies {
    implementation(project(":core"))
    implementation(libs.commons.text)
}
