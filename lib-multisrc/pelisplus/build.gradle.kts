plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    api(project(":lib:vudeo-extractor"))
    api(project(":lib:uqload-extractor"))
    api(project(":lib:streamwish-extractor"))
    api(project(":lib:filemoon-extractor"))
    api(project(":lib:streamlare-extractor"))
    api(project(":lib:yourupload-extractor"))
    api(project(":lib:streamtape-extractor"))
    api(project(":lib:dood-extractor"))
    api(project(":lib:voe-extractor"))
    api(project(":lib:okru-extractor"))
    api(project(":lib:mp4upload-extractor"))
    api(project(":lib:mixdrop-extractor"))
    api(project(":lib:burstcloud-extractor"))
    api(project(":lib:fastream-extractor"))
    api(project(":lib:upstream-extractor"))
    api(project(":lib:streamhidevid-extractor"))
    api(project(":lib:streamsilk-extractor"))
    api(project(":lib:vidguard-extractor"))
    api(project(":lib:universal-extractor"))
    api(project(":lib:vidhide-extractor"))
}
