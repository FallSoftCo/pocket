package co.fallsoft.pocket

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.jsoup.Jsoup

/** A review page is metadata, never an embedded browser or executable HTML. */
internal fun galleryPagePreview(html:String,page:URI):GalleryPreviewCard? {
    val document=Jsoup.parse(html)
    val title=document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        .ifBlank{document.title()}.ifBlank{document.selectFirst("h1")?.text().orEmpty()}.take(240)
    val candidates=listOfNotNull(document.selectFirst("meta[property=og:image]")?.attr("content"))+
        document.select("figure img[src], main img[src], article img[src]").map{it.attr("src")}+
        document.select("img[src]").map{it.attr("src")}
    // Resolve against the actual requested page, not an untrusted <base> tag.
    return candidates.firstNotNullOfOrNull { source ->
        GalleryCardLinks.asset(page,source)?.let{GalleryPreviewCard(title,it)}
    }
}

internal object GalleryPagePreviews {
    private data class Entry(val at:Long,val job:Deferred<GalleryPreviewCard?>)
    private val cache=LinkedHashMap<String,Entry>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val permits=Semaphore(2)
    private val http=Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12,TimeUnit.SECONDS).build()
    suspend fun resolve(uri:URI):GalleryPreviewCard? {
        val page=GalleryCardLinks.page(uri);val key=page.toString();val now=System.currentTimeMillis()
        val job=mutex.withLock{
            cache[key]?.takeIf{now-it.at<60_000}?.job?:scope.async{permits.withPermit{
                http.newCall(Request.Builder().url(key).get().build()).execute().use{response->
                    check(response.isSuccessful){"Gallery page unavailable"}
                    val type=response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    check(type in listOf("text/html","application/xhtml+xml")){"Not a gallery page"}
                    val body=requireNotNull(response.body);val limit=512*1024L
                    check(body.contentLength()<=limit){"Gallery page exceeds preview limit"}
                    val source=body.source();check(!source.request(limit+1)){"Gallery page exceeds preview limit"}
                    galleryPagePreview(source.readString(Charsets.UTF_8),page)
                }
            }}.also{cache[key]=Entry(now,it);while(cache.size>16)cache.remove(cache.keys.first())}
        }
        return job.await()
    }
    suspend fun invalidate(uri:URI){mutex.withLock{cache.remove(GalleryCardLinks.page(uri).toString())}}
}
