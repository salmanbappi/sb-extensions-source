import eu.kanade.tachiyomi.animeextension.en.zorotv.ZoroTv
import eu.kanade.tachiyomi.animesource.model.Hoster
import kotlinx.coroutines.runBlocking

fun main() {
    val zoro = ZoroTv()
    runBlocking {
        val videos = zoro.getVideoList(Hoster("Test", "https://tamilembed.lol/embed/stream/SWkxWVNXL2JCMmpOYmlLdHZDTFlBWHJHRlFtay8yR1V2Mm9wMVo3SVJqbDczWXpRbzRhcjF2NUpnWkNBa29rVjhReDNUTnpwUi9iMEZMNnFIbUJIeG81SWNnU0lnQ0V4czlQa1BsV0krVFJsRlZRaXNBMXR4VTRwZUZmd0ZYR2R0ZkRaeDNhRXJXcEJ3UlN4dWtRTjZnPT06OqxZd%2BcTYsNlJEY1MKr7t9I%3D"))
        println("Videos: $videos")
    }
}
