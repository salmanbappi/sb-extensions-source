import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request

fun main() {
    val client = OkHttpClient()
    val request = Request.Builder().url("https://google.com").build()
    client.newCall(request).execute().use { response ->
        val headers = response.headers("Set-Cookie")
        println(headers::class.simpleName)
    }
}
