fun encode(url: String): String {
    val fixedUrl = if (url.startsWith("//")) "https:$url" else url
    return java.net.URLEncoder.encode(fixedUrl, "UTF-8")
}
println(encode("//example.com/video.mp4"))
