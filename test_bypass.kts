import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

val formBody = "g-recaptcha-response=${UUID.randomUUID()}"
val url = URL("https://net52.cc/verify.php")
val conn = url.openConnection() as HttpURLConnection
conn.requestMethod = "POST"
conn.instanceFollowRedirects = false
conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
conn.setRequestProperty("Origin", "https://net22.cc")
conn.setRequestProperty("Referer", "https://net22.cc/verify2")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
conn.doOutput = true
conn.outputStream.write(formBody.toByteArray())

val cookies = conn.headerFields["Set-Cookie"]
println("Response Code: ${conn.responseCode}")
println("Cookies: $cookies")

